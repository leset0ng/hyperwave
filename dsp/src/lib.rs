pub mod artwork;

use rustfft::{num_complex::Complex32, Fft, FftPlanner};
use std::sync::Arc;

pub const FRAMES: usize = 4096;
pub const WINDOW: usize = 2048;
pub const BANDS: usize = 6;
const EDGES: [usize; 7] = [0, 6, 13, 44, 127, 383, 852];

/// Reconstructed six-band pipeline. A full block advances time by 4096 frames;
/// only its first 2048 frames are windowed, followed by 2048 zeroes.
pub struct Analyzer {
    fft: Arc<dyn Fft<f32>>,
    window: Vec<f32>,
    buffer: Vec<Complex32>,
    scratch: Vec<Complex32>,
    magnitudes: [f32; WINDOW],
}

impl Default for Analyzer {
    fn default() -> Self {
        let fft = FftPlanner::<f32>::new().plan_fft_forward(FRAMES);
        let scratch = vec![Complex32::default(); fft.get_inplace_scratch_len()];
        Self {
            fft,
            window: (0..WINDOW)
                .map(|n| (0.5 * (1.0 - (std::f64::consts::TAU * n as f64 / WINDOW as f64).cos())) as f32)
                .collect(),
            buffer: vec![Complex32::default(); FRAMES],
            scratch,
            magnitudes: [0.0; WINDOW],
        }
    }
}

impl Analyzer {
    pub fn analyze(&mut self, pcm: &[f32]) -> Result<[f32; BANDS], &'static str> {
        self.analyze_bins(pcm, EDGES[BANDS])
    }

    // Production only consumes bins 0..852; full spectra remain available to reference tests.
    fn analyze_bins(&mut self, pcm: &[f32], bins: usize) -> Result<[f32; BANDS], &'static str> {
        if pcm.len() != FRAMES {
            return Err("Expected exactly 4096 mono frames at 48000 Hz");
        }
        for (i, bin) in self.buffer.iter_mut().enumerate() {
            let sample = if i < WINDOW && pcm[i].is_finite() { pcm[i] * self.window[i] } else { 0.0 };
            *bin = Complex32::new(sample, 0.0);
        }
        self.fft.process_with_scratch(&mut self.buffer, &mut self.scratch);
        // vDSP real-forward output is twice a conventional unscaled FFT.
        // Its 2/2048 postscale therefore becomes 4/2048 here.
        for (magnitude, bin) in self.magnitudes[..bins].iter_mut().zip(&self.buffer) {
            *magnitude = bin.norm() * (4.0 / WINDOW as f32);
        }
        // vDSP packs DC and Nyquist into the real/imaginary parts of bin zero.
        self.magnitudes[0] = self.buffer[0].re.hypot(self.buffer[WINDOW].re)
            * (4.0 / WINDOW as f32);
        Ok(map_bands(&self.magnitudes))
    }
}

pub fn map_bands(magnitudes: &[f32; WINDOW]) -> [f32; BANDS] {
    std::array::from_fn(|band| {
        let sum: f32 = magnitudes[EDGES[band]..EDGES[band + 1]].iter().sum();
        let x = (0.6 * sum).powf(0.4) * (1.0 - 0.05 * band as f32);
        let polynomial = x * 3.8408 + (x * x) * -8.1824
            + x.powf(3.0) * 7.7723 + x.powf(4.0) * -2.4307;
        let value = x * polynomial;
        if value.is_finite() { value.clamp(0.0, 1.0) } else { 0.0 }
    })
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{objects::{JClass, JFloatArray}, sys::jboolean, JNIEnv};
    use std::{panic::{catch_unwind, AssertUnwindSafe}, sync::{Mutex, OnceLock}};

    struct Workspace {
        analyzer: Analyzer,
        pcm: [f32; FRAMES],
    }
    static WORKSPACE: OnceLock<Mutex<Workspace>> = OnceLock::new();

    #[no_mangle]
    pub extern "system" fn Java_dev_hyperwave_audio_NativeDsp_analyze(
        env: JNIEnv,
        _class: JClass,
        input: JFloatArray,
        output: JFloatArray,
    ) -> jboolean {
        catch_unwind(AssertUnwindSafe(|| -> Option<()> {
            if env.get_array_length(&input).ok()? != FRAMES as i32
                || env.get_array_length(&output).ok()? < BANDS as i32 { return None; }
            let mutex = WORKSPACE.get_or_init(|| Mutex::new(Workspace {
                analyzer: Analyzer::default(), pcm: [0.0; FRAMES],
            }));
            let mut workspace = mutex.lock().ok()?;
            env.get_float_array_region(&input, 0, &mut workspace.pcm).ok()?;
            let Workspace { analyzer, pcm } = &mut *workspace;
            let values = analyzer.analyze(pcm).ok()?;
            env.set_float_array_region(&output, 0, &values).ok()?;
            Some(())
        })).ok().flatten().is_some() as jboolean
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apple_reference_pcm_vectors() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!("../tests/data/pcm-golden-vectors.json")).unwrap();
        let mut analyzer = Analyzer::default();
        for vector in fixture["vectors"].as_array().unwrap() {
            let name = vector["name"].as_str().unwrap();
            let pcm: Vec<f32> = vector["pcm_float32"].as_array().unwrap().iter()
                .map(|v| v.as_f64().unwrap() as f32).collect();
            let actual = analyzer.analyze(&pcm).unwrap();
            let full_spectrum = analyzer.analyze_bins(&pcm, WINDOW).unwrap();
            assert_eq!(actual, full_spectrum, "{name}: production and full-spectrum bands differ");
            for (i, value) in actual.iter().enumerate() {
                let expected = vector["six_bars_reference_float32"][i].as_f64().unwrap() as f32;
                assert!((value - expected).abs() < 0.0001, "{name} band {i}: {value} != {expected}");
            }
            for (i, value) in analyzer.magnitudes.iter().enumerate() {
                let expected = vector["magnitudes_float32"][i].as_f64().unwrap() as f32;
                assert!((value - expected).abs() < 0.00001, "{name} bin {i}: {value} != {expected}");
            }
        }
    }

    #[test]
    fn second_half_is_not_in_the_window() {
        let mut pcm = [0.0; FRAMES];
        pcm[WINDOW..].fill(1.0);
        assert_eq!(Analyzer::default().analyze(&pcm).unwrap(), [0.0; BANDS]);
    }

    #[test]
    fn invalid_blocks_are_rejected_and_nonfinite_samples_are_silenced() {
        let mut analyzer = Analyzer::default();
        assert!(analyzer.analyze(&[0.0; WINDOW]).is_err());
        assert_eq!(analyzer.analyze(&[f32::NAN; FRAMES]).unwrap(), [0.0; BANDS]);
    }

    #[test]
    fn production_bands_do_not_depend_on_previous_high_bins() {
        let mut analyzer = Analyzer::default();
        analyzer.magnitudes.fill(f32::NAN);
        let mut pcm = [0.0; FRAMES];
        for (i, sample) in pcm.iter_mut().enumerate() {
            *sample = (std::f32::consts::TAU * 900.0 * i as f32 / 48000.0).sin() * 0.05;
        }
        let actual = analyzer.analyze(&pcm).unwrap();
        assert!(actual.iter().all(|value| value.is_finite()));
        assert!(analyzer.magnitudes[EDGES[BANDS]..].iter().all(|value| value.is_nan()));
        assert_eq!(actual, analyzer.analyze_bins(&pcm, WINDOW).unwrap());
    }

    #[test]
    #[ignore = "manual release-mode paired timing, not a performance assertion"]
    fn benchmark_required_bins() {
        use std::{hint::black_box, time::Instant};
        let pcm: Vec<f32> = (0..FRAMES).map(|i| (i as f32 * 0.1178).sin() * 0.05).collect();
        let mut analyzer = Analyzer::default();
        let iterations = 4000;
        let mut full = Vec::new();
        let mut required = Vec::new();
        for round in 0..13 {
            for bins in if round % 2 == 0 { [WINDOW, EDGES[BANDS]] } else { [EDGES[BANDS], WINDOW] } {
                let started = Instant::now();
                for _ in 0..iterations {
                    black_box(analyzer.analyze_bins(black_box(&pcm), black_box(bins)).unwrap());
                }
                if round >= 2 {
                    let elapsed = started.elapsed().as_secs_f64() * 1e6 / iterations as f64;
                    if bins == WINDOW { full.push(elapsed); } else { required.push(elapsed); }
                }
            }
        }
        full.sort_by(f64::total_cmp);
        required.sort_by(f64::total_cmp);
        println!("DSP median us/block: full={}, required={}, speedup={}", full[5], required[5], full[5] / required[5]);
    }

    #[test]
    fn band_edges_and_ignored_ultrasonic_bins() {
        let mut magnitudes = [0.0; WINDOW];
        magnitudes[13] = 0.1;
        let output = map_bands(&magnitudes);
        assert!(output[2] > 0.0);
        assert!(output.iter().enumerate().all(|(i, v)| i == 2 || *v == 0.0));
        magnitudes.fill(0.0);
        magnitudes[852] = 1.0;
        assert_eq!(map_bands(&magnitudes), [0.0; BANDS]);
    }
}
