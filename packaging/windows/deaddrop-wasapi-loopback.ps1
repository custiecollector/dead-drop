# DeadDrop Windows system-output capture helper.
# Streams WASAPI loopback audio from the default render endpoint as raw mono s16le 44100 Hz PCM on stdout.
# Do not write normal status text to stdout; DeadDrop treats stdout as audio samples.
param([switch]$CompileOnly)
$ErrorActionPreference = 'Stop'

$source = @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace DeadDropAudio {
    enum EDataFlow { eRender = 0, eCapture = 1, eAll = 2 }
    enum ERole { eConsole = 0, eMultimedia = 1, eCommunications = 2 }
    enum AUDCLNT_SHAREMODE { AUDCLNT_SHAREMODE_SHARED = 0, AUDCLNT_SHAREMODE_EXCLUSIVE = 1 }
    [Flags] enum AUDCLNT_STREAMFLAGS : uint { AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000 }
    [Flags] enum CLSCTX : uint { CLSCTX_INPROC_SERVER = 0x1, CLSCTX_INPROC_HANDLER = 0x2, CLSCTX_LOCAL_SERVER = 0x4, CLSCTX_REMOTE_SERVER = 0x10, CLSCTX_ALL = 0x17 }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorComObject { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int EnumAudioEndpoints(EDataFlow dataFlow, uint dwStateMask, IntPtr ppDevices);
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice ppEndpoint);
        int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string pwstrId, out IMMDevice ppDevice);
        int RegisterEndpointNotificationCallback(IntPtr pClient);
        int UnregisterEndpointNotificationCallback(IntPtr pClient);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        int Activate(ref Guid iid, CLSCTX dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
        int OpenPropertyStore(uint stgmAccess, IntPtr ppProperties);
        int GetId(out IntPtr ppstrId);
        int GetState(out uint pdwState);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient {
        int Initialize(AUDCLNT_SHAREMODE ShareMode, AUDCLNT_STREAMFLAGS StreamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, ref Guid AudioSessionGuid);
        int GetBufferSize(out uint pNumBufferFrames);
        int GetStreamLatency(out long phnsLatency);
        int GetCurrentPadding(out uint pNumPaddingFrames);
        int IsFormatSupported(AUDCLNT_SHAREMODE ShareMode, IntPtr pFormat, out IntPtr ppClosestMatch);
        int GetMixFormat(out IntPtr ppDeviceFormat);
        int GetDevicePeriod(out long phnsDefaultDevicePeriod, out long phnsMinimumDevicePeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr eventHandle);
        int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient {
        int GetBuffer(out IntPtr ppData, out uint pNumFramesToRead, out uint pdwFlags, out long pu64DevicePosition, out long pu64QPCPosition);
        int ReleaseBuffer(uint NumFramesRead);
        int GetNextPacketSize(out uint pNumFramesInNextPacket);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    struct WAVEFORMATEX {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    struct WAVEFORMATEXTENSIBLE {
        public WAVEFORMATEX Format;
        public ushort wValidBitsPerSample;
        public uint dwChannelMask;
        public Guid SubFormat;
    }

    public static class WasapiLoopback {
        const uint AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
        static readonly Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
        static readonly Guid IID_IAudioCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
        static readonly Guid KSDATAFORMAT_SUBTYPE_PCM = new Guid("00000001-0000-0010-8000-00aa00389b71");
        static readonly Guid KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = new Guid("00000003-0000-0010-8000-00aa00389b71");

        public static int Main() {
            try {
                Run();
                return 0;
            } catch (Exception ex) {
                Console.Error.WriteLine(ex.Message);
                return 1;
            }
        }

        public static void Run() {
            IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            IMMDevice device;
            Throw(enumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eConsole, out device), "GetDefaultAudioEndpoint");

            object audioObj;
            Guid audioClientId = IID_IAudioClient;
            Throw(device.Activate(ref audioClientId, CLSCTX.CLSCTX_ALL, IntPtr.Zero, out audioObj), "Activate IAudioClient");
            IAudioClient audioClient = (IAudioClient)audioObj;

            IntPtr mixPtr;
            Throw(audioClient.GetMixFormat(out mixPtr), "GetMixFormat");
            WAVEFORMATEX fmt = (WAVEFORMATEX)Marshal.PtrToStructure(mixPtr, typeof(WAVEFORMATEX));
            Guid subFormat = Guid.Empty;
            if (fmt.wFormatTag == 0xFFFE) {
                WAVEFORMATEXTENSIBLE ext = (WAVEFORMATEXTENSIBLE)Marshal.PtrToStructure(mixPtr, typeof(WAVEFORMATEXTENSIBLE));
                subFormat = ext.SubFormat;
            }

            Guid session = Guid.Empty;
            Throw(audioClient.Initialize(AUDCLNT_SHAREMODE.AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS.AUDCLNT_STREAMFLAGS_LOOPBACK, 1000000, 0, mixPtr, ref session), "Initialize loopback");
            Marshal.FreeCoTaskMem(mixPtr);

            object captureObj;
            Guid captureId = IID_IAudioCaptureClient;
            Throw(audioClient.GetService(ref captureId, out captureObj), "GetService IAudioCaptureClient");
            IAudioCaptureClient capture = (IAudioCaptureClient)captureObj;

            int rate = (int)fmt.nSamplesPerSec;
            int channels = Math.Max(1, (int)fmt.nChannels);
            int bits = Math.Max(8, (int)fmt.wBitsPerSample);
            int blockAlign = Math.Max(1, (int)fmt.nBlockAlign);
            bool isFloat = fmt.wFormatTag == 3 || (fmt.wFormatTag == 0xFFFE && subFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
            bool isPcm = fmt.wFormatTag == 1 || (fmt.wFormatTag == 0xFFFE && (subFormat == Guid.Empty || subFormat == KSDATAFORMAT_SUBTYPE_PCM));
            if (!isFloat && !isPcm) throw new InvalidOperationException("Unsupported WASAPI mix format tag=" + fmt.wFormatTag + " bits=" + bits + " subFormat=" + subFormat);

            LinearResampler resampler = new LinearResampler(rate, 44100);
            Stream stdout = Console.OpenStandardOutput();
            Throw(audioClient.Start(), "Start");
            try {
                while (true) {
                    uint packetFrames;
                    Throw(capture.GetNextPacketSize(out packetFrames), "GetNextPacketSize");
                    if (packetFrames == 0) {
                        Thread.Sleep(8);
                        continue;
                    }
                    while (packetFrames > 0) {
                        IntPtr data;
                        uint frames;
                        uint flags;
                        long devPos;
                        long qpc;
                        Throw(capture.GetBuffer(out data, out frames, out flags, out devPos, out qpc), "GetBuffer");
                        try {
                            float[] mono;
                            if ((flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || data == IntPtr.Zero) {
                                mono = new float[frames];
                            } else {
                                int byteCount = checked((int)frames * blockAlign);
                                byte[] raw = new byte[byteCount];
                                Marshal.Copy(data, raw, 0, byteCount);
                                mono = ConvertToMono(raw, (int)frames, channels, bits, blockAlign, isFloat);
                            }
                            byte[] pcm = resampler.ToS16Le(mono);
                            if (pcm.Length > 0) {
                                stdout.Write(pcm, 0, pcm.Length);
                                stdout.Flush();
                            }
                        } finally {
                            Throw(capture.ReleaseBuffer(frames), "ReleaseBuffer");
                        }
                        Throw(capture.GetNextPacketSize(out packetFrames), "GetNextPacketSize");
                    }
                }
            } finally {
                audioClient.Stop();
            }
        }

        static float[] ConvertToMono(byte[] raw, int frames, int channels, int bits, int blockAlign, bool isFloat) {
            float[] mono = new float[frames];
            int bytesPerSample = Math.Max(1, bits / 8);
            for (int f = 0; f < frames; f++) {
                int frameOff = f * blockAlign;
                double sum = 0.0;
                for (int c = 0; c < channels; c++) {
                    int off = frameOff + c * bytesPerSample;
                    if (off + bytesPerSample > raw.Length) break;
                    sum += ReadSample(raw, off, bits, isFloat);
                }
                mono[f] = (float)(sum / channels);
            }
            return mono;
        }

        static double ReadSample(byte[] raw, int off, int bits, bool isFloat) {
            if (isFloat && bits == 32) return Math.Max(-1.0, Math.Min(1.0, BitConverter.ToSingle(raw, off)));
            if (bits == 16) return BitConverter.ToInt16(raw, off) / 32768.0;
            if (bits == 24) {
                int v = raw[off] | (raw[off + 1] << 8) | (raw[off + 2] << 16);
                if ((v & 0x800000) != 0) v |= unchecked((int)0xFF000000);
                return v / 8388608.0;
            }
            if (bits == 32) return BitConverter.ToInt32(raw, off) / 2147483648.0;
            if (bits == 8) return (raw[off] - 128) / 128.0;
            return 0.0;
        }

        static void Throw(int hr, string op) {
            if (hr < 0) Marshal.ThrowExceptionForHR(hr);
        }
    }

    class LinearResampler {
        readonly double step;
        double pos;
        bool haveLast;
        float last;

        public LinearResampler(int inputRate, int outputRate) {
            step = inputRate / (double)outputRate;
            pos = 0.0;
        }

        public byte[] ToS16Le(float[] input) {
            if (input == null || input.Length == 0) return new byte[0];
            int prefix = haveLast ? 1 : 0;
            float[] src = new float[input.Length + prefix];
            if (haveLast) src[0] = last;
            Array.Copy(input, 0, src, prefix, input.Length);

            MemoryStream ms = new MemoryStream(Math.Max(256, input.Length * 2));
            while (pos < src.Length - 1) {
                int i = (int)pos;
                double frac = pos - i;
                double sample = src[i] + (src[i + 1] - src[i]) * frac;
                if (sample > 1.0) sample = 1.0;
                if (sample < -1.0) sample = -1.0;
                short s = (short)Math.Round(sample * 32767.0);
                ms.WriteByte((byte)(s & 0xff));
                ms.WriteByte((byte)((s >> 8) & 0xff));
                pos += step;
            }
            pos -= Math.Max(1, src.Length - 1);
            if (pos < 0) pos = 0;
            last = src[src.Length - 1];
            haveLast = true;
            return ms.ToArray();
        }
    }
}
'@

Add-Type -TypeDefinition $source -Language CSharp
if ($CompileOnly) { exit 0 }
[Environment]::Exit([DeadDropAudio.WasapiLoopback]::Main())
