use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc::Sender;

/// Target format for the backend STT proxy: 16 kHz mono PCM16, ~250ms frames.
pub const SAMPLE_RATE: u32 = 16_000;
const CHUNK_SAMPLES: usize = 4_000; // 250ms

/// Handle to a live mic capture. Dropping it does NOT stop capture — call stop().
pub struct Recorder {
    stop: Arc<AtomicBool>,
}

impl Recorder {
    /// Signals the capture thread to end. The audio channel closes shortly after,
    /// which is how the STT session knows to send "finish".
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

/// Opens the default input device and streams 16kHz mono PCM16 chunks into `tx`.
///
/// cpal streams are !Send on macOS, so the stream lives on its own thread for
/// its whole life; the thread polls the stop flag and drops the stream on stop.
pub fn start(tx: Sender<Vec<u8>>) -> Result<Recorder, String> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or("no microphone found")?;
    let config = device
        .default_input_config()
        .map_err(|e| format!("mic config: {e}"))?;

    let stop = Arc::new(AtomicBool::new(false));
    let stop_thread = stop.clone();
    let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<(), String>>();

    std::thread::spawn(move || {
        let channels = config.channels() as usize;
        let in_rate = config.sample_rate().0;
        let mut resampler = Resampler::new(in_rate);
        let err_fn = |e| eprintln!("mic stream error: {e}");

        let on_mono = move |mono: &[f32]| {
            resampler.push(mono, |chunk| {
                // try_send: dropping a frame under backpressure beats stalling
                // the audio callback; capacity 64 is ~16s, so it never happens.
                let _ = tx.try_send(chunk);
            });
        };
        let stream = match config.sample_format() {
            cpal::SampleFormat::F32 => build_stream::<f32>(&device, &config.clone().into(), channels, on_mono, err_fn),
            cpal::SampleFormat::I16 => build_stream::<i16>(&device, &config.clone().into(), channels, on_mono, err_fn),
            cpal::SampleFormat::U16 => build_stream::<u16>(&device, &config.clone().into(), channels, on_mono, err_fn),
            f => Err(format!("unsupported sample format {f}")),
        };
        let stream = match stream {
            Ok(s) => s,
            Err(e) => {
                let _ = ready_tx.send(Err(e));
                return;
            }
        };
        if let Err(e) = stream.play() {
            let _ = ready_tx.send(Err(format!("mic start: {e}")));
            return;
        }
        let _ = ready_tx.send(Ok(()));
        while !stop_thread.load(Ordering::Relaxed) {
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        drop(stream); // closes the mic; tx drops with the closure → channel closes
    });

    ready_rx
        .recv()
        .map_err(|_| "mic thread died".to_string())??;
    Ok(Recorder { stop })
}

fn build_stream<T>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    channels: usize,
    mut on_mono: impl FnMut(&[f32]) + Send + 'static,
    err_fn: impl Fn(cpal::StreamError) + Send + 'static,
) -> Result<cpal::Stream, String>
where
    T: cpal::SizedSample,
    f32: cpal::FromSample<T>,
{
    let mut mono = Vec::new();
    device
        .build_input_stream(
            config,
            move |data: &[T], _| {
                mono.clear();
                for frame in data.chunks(channels) {
                    let sum: f32 = frame
                        .iter()
                        .map(|s| <f32 as cpal::FromSample<T>>::from_sample_(*s))
                        .sum();
                    mono.push(sum / channels as f32);
                }
                on_mono(&mono);
            },
            err_fn,
            None,
        )
        .map_err(|e| format!("mic stream: {e}"))
}

/// Linear-interpolation downsampler to 16kHz, emitting PCM16 LE in 250ms chunks.
/// Linear is fine for speech into an STT model; a polyphase filter is D2 polish.
struct Resampler {
    step: f64,
    pos: f64,
    buf: Vec<f32>,
    out: Vec<i16>,
}

impl Resampler {
    fn new(in_rate: u32) -> Self {
        Self {
            step: in_rate as f64 / SAMPLE_RATE as f64,
            pos: 0.0,
            buf: Vec::new(),
            out: Vec::with_capacity(CHUNK_SAMPLES),
        }
    }

    fn push(&mut self, mono: &[f32], mut emit: impl FnMut(Vec<u8>)) {
        self.buf.extend_from_slice(mono);
        while (self.pos as usize) + 1 < self.buf.len() {
            let i = self.pos as usize;
            let frac = self.pos - i as f64;
            let s = self.buf[i] as f64 * (1.0 - frac) + self.buf[i + 1] as f64 * frac;
            self.out.push((s.clamp(-1.0, 1.0) * 32767.0) as i16);
            if self.out.len() >= CHUNK_SAMPLES {
                emit(pcm16_bytes(&self.out));
                self.out.clear();
            }
            self.pos += self.step;
        }
        let consumed = self.pos as usize;
        self.buf.drain(..consumed);
        self.pos -= consumed as f64;
    }
}

fn pcm16_bytes(samples: &[i16]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(samples.len() * 2);
    for s in samples {
        bytes.extend_from_slice(&s.to_le_bytes());
    }
    bytes
}
