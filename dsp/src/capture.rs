use crate::{Analyzer, BANDS, FRAMES};

/// A reader returns bytes, matching AudioRecord's direct-buffer overload.
pub(crate) trait CaptureIo {
    fn stopped(&mut self) -> Result<bool, String>;
    fn read(&mut self, remaining: &mut [f32]) -> Result<i32, String>;
    fn on_block(&mut self, bars: &[f32; BANDS], peak: f32, block: i64) -> Result<(), String>;
}

pub(crate) fn run(io: &mut impl CaptureIo, pcm: &mut [f32; FRAMES]) -> Result<(), String> {
    let mut analyzer = Analyzer::default();
    let mut offset = 0;
    let mut blocks = 0;
    while !io.stopped()? {
        let remaining = &mut pcm[offset..];
        let count = io.read(remaining)?;
        // stop() unblocks a pending read, which may return partial data or an error.
        if io.stopped()? {
            return Ok(());
        }
        if count <= 0 {
            return Err(format!("AudioRecord.read={count}"));
        }
        let bytes = count as usize;
        if bytes > std::mem::size_of_val(remaining) || bytes % size_of::<f32>() != 0 {
            return Err(format!("Invalid AudioRecord byte count={count}"));
        }
        offset += bytes / size_of::<f32>();
        if offset != FRAMES {
            continue;
        }
        let bars = analyzer.analyze(pcm).map_err(str::to_owned)?;
        blocks += 1;
        let peak = if blocks == 1 || blocks % 24 == 0 {
            pcm.iter().fold(0.0_f32, |peak, sample| peak.max(sample.abs()))
        } else {
            0.0
        };
        io.on_block(&bars, peak, blocks)?;
        offset = 0;
    }
    Ok(())
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{
        objects::{JClass, JMethodID, JObject},
        signature::{Primitive, ReturnType},
        sys::jvalue,
        JNIEnv,
    };
    use std::panic::{catch_unwind, AssertUnwindSafe};

    struct AudioRecordIo<'local, 'obj> {
        env: JNIEnv<'local>,
        record: &'obj JObject<'local>,
        callback: &'obj JObject<'local>,
        read: JMethodID,
        stopped: JMethodID,
        on_block: JMethodID,
    }

    impl CaptureIo for AudioRecordIo<'_, '_> {
        fn stopped(&mut self) -> Result<bool, String> {
            // IDs and signatures are resolved from these exact objects before the loop.
            unsafe {
                self.env.call_method_unchecked(self.callback, self.stopped,
                    ReturnType::Primitive(Primitive::Boolean), &[])
            }.and_then(|value| value.z()).map_err(|error| error.to_string())
        }

        fn read(&mut self, remaining: &mut [f32]) -> Result<i32, String> {
            let bytes = std::mem::size_of_val(remaining);
            // AudioRecord ignores ByteBuffer.position(). Each view starts at the
            // unfilled region and is derived from this read's active mutable borrow.
            // It is deleted before that borrow ends, including for full-block reads.
            let view = unsafe {
                self.env.new_direct_byte_buffer(remaining.as_mut_ptr().cast(), bytes)
            }.map_err(|error| error.to_string())?;
            let buffer = self.env.auto_local(view);
            // read(ByteBuffer, sizeInBytes, READ_BLOCKING=0) writes native-endian PCM.
            unsafe {
                self.env.call_method_unchecked(self.record, self.read,
                    ReturnType::Primitive(Primitive::Int), &[
                        jvalue { l: buffer.as_raw() },
                        jvalue { i: bytes as i32 },
                        jvalue { i: 0 },
                    ])
            }.and_then(|value| value.i()).map_err(|error| error.to_string())
        }

        fn on_block(&mut self, bars: &[f32; BANDS], peak: f32, block: i64) -> Result<(), String> {
            // Each delivery owns its array: the UI may still have an earlier one queued.
            // AutoLocal bounds references even though this JNI invocation is long-lived.
            let array = self.env.new_float_array(BANDS as i32).map_err(|error| error.to_string())?;
            let array = self.env.auto_local(array);
            self.env.set_float_array_region(&array, 0, bars).map_err(|error| error.to_string())?;
            unsafe {
                self.env.call_method_unchecked(self.callback, self.on_block,
                    ReturnType::Primitive(Primitive::Void), &[
                        jvalue { l: array.as_raw() },
                        jvalue { f: peak },
                        jvalue { j: block },
                    ])
            }.map(|_| ()).map_err(|error| error.to_string())
        }
    }

    fn capture<'local>(mut env: JNIEnv<'local>, record: JObject<'local>, callback: JObject<'local>)
            -> Result<(), String> {
        let record_class = env.get_object_class(&record).map_err(|error| error.to_string())?;
        let callback_class = env.get_object_class(&callback).map_err(|error| error.to_string())?;
        let read = env.get_method_id(&record_class, "read", "(Ljava/nio/ByteBuffer;II)I")
            .map_err(|error| error.to_string())?;
        let stopped = env.get_method_id(&callback_class, "isStopped", "()Z")
            .map_err(|error| error.to_string())?;
        let on_block = env.get_method_id(&callback_class, "onBlock", "([FFJ)V")
            .map_err(|error| error.to_string())?;
        let mut pcm = Box::new([0.0; FRAMES]);
        let mut io = AudioRecordIo { env, record: &record, callback: &callback,
            read, stopped, on_block };
        run(&mut io, &mut pcm)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_hyperwave_audio_NativeDsp_capture<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        record: JObject<'local>,
        callback: JObject<'local>,
    ) {
        // Keep the original environment for reporting failures; never replace a
        // pending Java exception or let Rust unwind through JNI.
        let result = catch_unwind(AssertUnwindSafe(|| {
            let local_env = unsafe { env.unsafe_clone() };
            capture(local_env, record, callback)
        }));
        let error = match result {
            Ok(Ok(())) => return,
            Ok(Err(error)) => error,
            Err(_) => "Rust capture loop panicked".to_owned(),
        };
        if !env.exception_check().unwrap_or(true) {
            let _ = env.throw_new("java/lang/IllegalStateException", error);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    struct Reader {
        source: Vec<f32>,
        chunks: VecDeque<i32>,
        position: usize,
        stopped: bool,
        stop_in_read: bool,
        blocks: Vec<([f32; BANDS], f32, i64)>,
    }

    impl CaptureIo for Reader {
        fn stopped(&mut self) -> Result<bool, String> { Ok(self.stopped) }
        fn read(&mut self, remaining: &mut [f32]) -> Result<i32, String> {
            let count = self.chunks.pop_front().expect("unexpected read");
            self.stopped |= self.stop_in_read;
            if count > 0 && count as usize <= std::mem::size_of_val(remaining) && count % 4 == 0 {
                let frames = count as usize / 4;
                remaining[..frames].copy_from_slice(&self.source[self.position..self.position + frames]);
                self.position += frames;
            }
            Ok(count)
        }
        fn on_block(&mut self, bars: &[f32; BANDS], peak: f32, block: i64) -> Result<(), String> {
            self.blocks.push((*bars, peak, block));
            if self.chunks.is_empty() { self.stopped = true; }
            Ok(())
        }
    }

    fn reader(chunks: &[i32]) -> Reader {
        Reader {
            source: (0..FRAMES * 2).map(|i| (i as f32 * 0.1178).sin() * 0.05).collect(),
            chunks: chunks.iter().copied().collect(), position: 0,
            stopped: false, stop_in_read: false, blocks: Vec::new(),
        }
    }

    #[test]
    fn partial_reads_preserve_every_sample_and_block_boundary() {
        let mut io = reader(&[400, 1200, 14784, 16384]);
        let mut pcm = [0.0; FRAMES];
        run(&mut io, &mut pcm).unwrap();
        assert_eq!(io.blocks.len(), 2);
        for (i, (bars, peak, block)) in io.blocks.iter().enumerate() {
            let input = &io.source[i * FRAMES..(i + 1) * FRAMES];
            assert_eq!(*bars, Analyzer::default().analyze(input).unwrap());
            assert_eq!(*block, i as i64 + 1);
            if i == 0 { assert_eq!(*peak, input.iter().fold(0.0_f32, |a, b| a.max(b.abs()))); }
        }
    }

    #[test]
    fn invalid_reads_fail_without_publishing_partial_pcm() {
        for count in [0, -3, -6, 1, 16388] {
            let mut io = reader(&[count]);
            assert!(run(&mut io, &mut [0.0; FRAMES]).is_err(), "count={count}");
            assert!(io.blocks.is_empty());
        }
        let mut io = reader(&[400, -6]);
        assert!(run(&mut io, &mut [0.0; FRAMES]).is_err());
        assert!(io.blocks.is_empty());
    }

    #[test]
    fn cancellation_before_or_during_read_never_publishes_a_block() {
        let mut io = reader(&[]);
        io.stopped = true;
        run(&mut io, &mut [0.0; FRAMES]).unwrap();
        for count in [-3, 400, 16384] {
            let mut io = reader(&[count]);
            io.stop_in_read = true;
            run(&mut io, &mut [0.0; FRAMES]).unwrap();
            assert!(io.blocks.is_empty());
        }
    }
}
