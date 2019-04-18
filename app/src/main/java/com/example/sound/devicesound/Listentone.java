package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import static java.lang.Math.*;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;

    // decode.py의 dominant 부분 구현
    private double findFrequency(double[] toTransform){
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        Double[] freq = this.fftfreq(complx.length, 1);

        for(int i = 0; i < complx.length; i++){
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
        }
        // peak_coeff 구하는 부분
        double peak_coeff = 0;
        double peak_freq = 0;
        int x = 0;
        for(int i = 0; i<mag.length; i++){
            if(mag[i] > peak_coeff){
                peak_coeff = mag[i];
                // peak_freq 구하는 부분
                peak_freq = freq[i];
            }
        }
        return Math.abs(peak_freq * mSampleRate);
    }

    private Double[] fftfreq(int n, double d){
        // numpy.fft.freq 구현
        Double[] f = new Double[n];
        int i = 0;
        if(n%2 == 0){ // if n is even
            for(; i< n/2; i++){
                f[i] = i / (d*n);
            }
            for(int j = n/2; j<n; j++){
                if(i > 0) {
                    f[j] = -i / (d * n);
                    i--;
                }
            }
        }
        if(n%2 == 1){ // if n is odd
            for(; i< (n-1)/2; i++){
                f[i] = i / (d*n);
            }
            for(int j = (n-1)/2; j< n; j++){
                if(i>0) {
                    f[j] = -i / (d * n);
                    i--;
                }
            }
        }
        return f;
    }

    public ArrayList extract_packet(ArrayList<Double> freqs) {
        // decode.py의 extract_packet 구현
        ArrayList<Double> freqs_2 = new ArrayList();
        ArrayList<Integer> bit_chunks = new ArrayList();
        ArrayList<Integer> bit_chunks_2 = new ArrayList();

        // freqs = freqs[::2] 구현
        for(int i = 0; i < freqs.size(); i = i+2){
            freqs_2.add(freqs.get(i));
        }

        // bit_chunks = [int(round((f - START_HZ) / STEP_HZ)) for f in freqs] 구현
        for(int i = 0; i < freqs_2.size(); i++){
            bit_chunks.add((int)(Math.round((freqs.get(i)-START_HZ) / STEP_HZ)));
        }

        // bit_chunks = [c for c in bit_chunks[1:] if 0 <= c < (2 ** BITS)] 구현
        for(int i = 1; i < bit_chunks.size(); i++){
            if(bit_chunks.get(i) >= 0 && bit_chunks.get(i) < 2*BITS*BITS)
                bit_chunks_2.add(bit_chunks.get(i));
        }
        return decode_bitchunks(BITS, bit_chunks_2);
    }

    //decode.py의 decode_bitchunks 부분 구현
    private ArrayList decode_bitchunks(int chunk_bits, ArrayList chunks){
        ArrayList out_bytes = new ArrayList();

        int next_read_chunk = 0;
        int next_read_bit = 0;

        int byte_a = 0;
        int bits_left = 8;
        while(next_read_chunk < chunks.size()){
            int can_fill = chunk_bits - next_read_bit;
            int to_fill = min(bits_left, can_fill);
            int offset = chunk_bits - next_read_bit - to_fill;
            byte_a <<= to_fill;
            int shifted = (int)chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            byte_a = (byte_a | shifted) >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if (bits_left <= 0) {
                out_bytes.add(byte_a);
                byte_a = 0;
                bits_left = 8;
            }
            if(next_read_bit >= chunk_bits){
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }
        return out_bytes;
    }

    private boolean match(int freq1, int freq2){
        // freq1 - freq2의 절대값이 20 미만인지 판단
        return abs(freq1 - freq2) < 20;
    }

    // x의 가장 가까운 제곱수를 구하는 함수
    private int findPowerSize(int x){
        int powersize = 1;

        while(powersize<= x){
            powersize *= 2;
        }

        return powersize;
    }

    public Listentone(){

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();
    }

    // decode.py의 listen_tone 부분 구현
    public void PreRequest() {

        int blocksize = findPowerSize((int) (long) Math.round(interval / 2 * mSampleRate));
        short[] buffer = new short[blocksize];

        boolean in_packet = false;
        double[] chunk = new double[blocksize];
        ArrayList packet = new ArrayList();
        ArrayList byte_stream = new ArrayList();

        while(true){
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);

            if(bufferedReadResult < 0)
                continue;

            for(int i = 0; i < blocksize; i++){
                chunk[i] = buffer[i];
            }

            double dom = findFrequency(chunk);

            if (match((int)dom, HANDSHAKE_END_HZ)){
                byte_stream = extract_packet(packet);
                Log.d("byte_stream", byte_stream.toString());

                String finalprint = "";
                for(int i = 0; i < byte_stream.size(); i++){
                    finalprint += (char)(int)byte_stream.get(i);
                }

                Log.d("Listentone", finalprint.toString());

                in_packet = false;
                packet = new ArrayList();
            }
            else if(in_packet) {
                packet.add(dom);
            }
            else if(match((int)dom, HANDSHAKE_START_HZ)) {
                in_packet = true;
            }

        }
    }

}
