//! Pixel kernels preserve the Java artwork pipeline's sRGB channel arithmetic.

pub const SIZE: usize = 96;
pub const PIXELS: usize = SIZE * SIZE;

/// Accumulate in row order, including across JNI tiles, to retain rounding behavior.
pub fn brightness_sum(argb: &[i32], initial: f64) -> f64 {
    argb.iter().fold(initial, |sum, &color| {
        let color = color as u32;
        let r = ((color >> 16) & 255) as f64 / 255.0;
        let g = ((color >> 8) & 255) as f64 / 255.0;
        let b = (color & 255) as f64 / 255.0;
        sum + (0.299 * r * r + 0.587 * g * g + 0.114 * b * b).sqrt()
    })
}

/// Three separable box filters, with edge replication and truncation after each pass.
pub fn blur(pixels: &mut [i32; PIXELS], scratch: &mut [i32; PIXELS], radius: usize) -> bool {
    if !(1..=SIZE).contains(&radius) {
        return false;
    }
    for _ in 0..3 {
        box_filter(pixels, scratch, radius, false);
        box_filter(scratch, pixels, radius, true);
    }
    true
}

fn box_filter(source: &[i32; PIXELS], target: &mut [i32; PIXELS], radius: usize, vertical: bool) {
    let count = (2 * radius + 1) as u32;
    let stride = if vertical { SIZE } else { 1 };
    for line in 0..SIZE {
        let base = if vertical { line } else { line * SIZE };
        let mut sums = [0u32; 4];
        for position in 0..=2 * radius {
            let index = position.saturating_sub(radius).min(SIZE - 1);
            let color = source[base + index * stride] as u32;
            for (channel, sum) in sums.iter_mut().enumerate() {
                *sum += (color >> (channel * 8)) & 255;
            }
        }
        for position in 0..SIZE {
            let mut color = 0u32;
            for (channel, sum) in sums.iter().enumerate() {
                color |= (sum / count) << (channel * 8);
            }
            target[base + position * stride] = color as i32;
            let remove = position.saturating_sub(radius);
            let add = (position + radius + 1).min(SIZE - 1);
            let old = source[base + remove * stride] as u32;
            let new = source[base + add * stride] as u32;
            for (channel, sum) in sums.iter_mut().enumerate() {
                *sum += (new >> (channel * 8)) & 255;
                *sum -= (old >> (channel * 8)) & 255;
            }
        }
    }
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{objects::{JClass, JIntArray}, sys::{jboolean, jdouble, jint}, JNIEnv};
    use std::{panic::{catch_unwind, AssertUnwindSafe}, sync::{Mutex, OnceLock}};

    // Independent of the audio workspace: image work must never hold the DSP lock.
    struct Workspace {
        input: Vec<i32>,
        pixels: [i32; PIXELS],
        scratch: [i32; PIXELS],
    }
    static WORKSPACE: OnceLock<Mutex<Workspace>> = OnceLock::new();

    fn workspace() -> &'static Mutex<Workspace> {
        WORKSPACE.get_or_init(|| Mutex::new(Workspace {
            input: Vec::new(), pixels: [0; PIXELS], scratch: [0; PIXELS],
        }))
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_hyperwave_audio_NativeDsp_sumArtworkBrightness(
        env: JNIEnv,
        _class: JClass,
        input: JIntArray,
        count: jint,
        initial: jdouble,
    ) -> jdouble {
        catch_unwind(AssertUnwindSafe(|| -> Option<f64> {
            if count <= 0 || count > env.get_array_length(&input).ok()?
                || !initial.is_finite() || initial < 0.0 { return None; }
            let mut workspace = workspace().lock().ok()?;
            let count = count as usize;
            let additional = count.saturating_sub(workspace.input.len());
            workspace.input.try_reserve(additional).ok()?;
            workspace.input.resize(count, 0);
            env.get_int_array_region(&input, 0, &mut workspace.input).ok()?;
            Some(brightness_sum(&workspace.input, initial))
        })).ok().flatten().unwrap_or(-1.0)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_hyperwave_audio_NativeDsp_blurArtwork(
        env: JNIEnv,
        _class: JClass,
        input: JIntArray,
        radius: jint,
    ) -> jboolean {
        catch_unwind(AssertUnwindSafe(|| -> Option<()> {
            if !(1..=SIZE as jint).contains(&radius)
                || env.get_array_length(&input).ok()? < PIXELS as jint { return None; }
            let mut workspace = workspace().lock().ok()?;
            env.get_int_array_region(&input, 0, &mut workspace.pixels).ok()?;
            let Workspace { pixels, scratch, .. } = &mut *workspace;
            if !blur(pixels, scratch, radius as usize) { return None; }
            env.set_int_array_region(&input, 0, pixels).ok()?;
            Some(())
        })).ok().flatten().is_some() as jboolean
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Direct convolution is intentionally independent of the sliding-window kernel.
    fn reference_pass(source: &[i32; PIXELS], radius: usize, vertical: bool) -> [i32; PIXELS] {
        let mut target = [0; PIXELS];
        for y in 0..SIZE {
            for x in 0..SIZE {
                let mut sums = [0u32; 4];
                for offset in -(radius as isize)..=radius as isize {
                    let sx = if vertical { x } else { (x as isize + offset).clamp(0, SIZE as isize - 1) as usize };
                    let sy = if vertical { (y as isize + offset).clamp(0, SIZE as isize - 1) as usize } else { y };
                    let color = source[sy * SIZE + sx] as u32;
                    for (channel, sum) in sums.iter_mut().enumerate() {
                        *sum += (color >> (8 * channel)) & 255;
                    }
                }
                target[y * SIZE + x] = sums.iter().enumerate().fold(0u32, |color, (channel, sum)| {
                    color | (sum / (2 * radius + 1) as u32) << (8 * channel)
                }) as i32;
            }
        }
        target
    }

    #[test]
    fn brightness_uses_hsp_and_ignores_alpha() {
        let pixels: [i32; 5] = [0x00000000, 0x00ffffff, 0x00ff0000, 0x0000ff00, 0x000000ff];
        let expected = 1.0 + 0.299f64.sqrt() + 0.587f64.sqrt() + 0.114f64.sqrt();
        assert!((brightness_sum(&pixels, 0.0) - expected).abs() < 1e-14);
        let opaque: Vec<i32> = pixels.iter().map(|&pixel| (pixel as u32 | 0xff000000) as i32).collect();
        assert_eq!(brightness_sum(&opaque, 0.0), brightness_sum(&pixels, 0.0));
        assert_eq!(brightness_sum(&pixels[2..], brightness_sum(&pixels[..2], 0.0)), brightness_sum(&pixels, 0.0));
    }

    #[test]
    fn blur_matches_direct_convolution_at_both_display_sizes() {
        let input = std::array::from_fn(|i| (i as u32).wrapping_mul(2_654_435_761) as i32);
        for radius in [26, 36] {
            let mut actual = input;
            let mut scratch = [0; PIXELS];
            assert!(blur(&mut actual, &mut scratch, radius));
            let mut expected = input;
            for _ in 0..3 {
                expected = reference_pass(&reference_pass(&expected, radius, false), radius, true);
            }
            assert_eq!(actual, expected, "radius {radius}");
        }
    }

    #[test]
    fn blur_preserves_constant_pixels_and_rejects_invalid_radii() {
        let mut pixels = [0x7f19375b; PIXELS];
        let mut scratch = [0; PIXELS];
        for radius in [1, SIZE] {
            assert!(blur(&mut pixels, &mut scratch, radius));
            assert_eq!(pixels, [0x7f19375b; PIXELS]);
        }
        for radius in [0, SIZE + 1, usize::MAX] {
            assert!(!blur(&mut pixels, &mut scratch, radius));
            assert_eq!(pixels, [0x7f19375b; PIXELS]);
        }
    }
}
