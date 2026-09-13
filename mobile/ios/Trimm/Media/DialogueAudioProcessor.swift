import Foundation
import CoreMedia
import AVFoundation

class DialogueLeveler {
    private let target: Float
    private let cleanup: Float
    private let sampleRate: Int
    private let windowSamples: Int
    
    private var energy: [Float]
    private var energyIndex: Int = 0
    private var energyCount: Int = 0
    private var energySum: Double = 0.0
    private var envelope: Float = 0.0
    private var gainDb: Float = 0.0
    
    init(targetLufs: Float, cleanupStrength: Float, sampleRate: Int) {
        self.target = max(-24.0, min(-12.0, targetLufs))
        self.cleanup = max(0.0, min(1.0, cleanupStrength))
        self.sampleRate = sampleRate
        self.windowSamples = max(1, sampleRate * 3)
        self.energy = [Float](repeating: 0.0, count: windowSamples)
    }
    
    func process(sample: Float) -> Float {
        let magnitude = abs(sample)
        if magnitude > envelope {
            envelope = envelope * 0.82 + magnitude * 0.18
        } else {
            envelope = envelope * 0.995 + magnitude * 0.005
        }
        
        let gateThreshold = 0.002 + cleanup * 0.004
        if magnitude >= gateThreshold {
            addEnergy(value: sample * sample)
        }
        
        let measuredLufs: Float
        if energyCount == 0 {
            measuredLufs = target
        } else {
            let avgEnergy = max(1e-9, energySum / Double(energyCount))
            measuredLufs = Float(-0.691 + 10.0 * log10(avgEnergy))
        }
        
        let desiredGainDb = max(-12.0, min(9.0, target - measuredLufs))
        let timeSeconds = 1.0 / Float(sampleRate)
        let timeConstant: Float = (desiredGainDb < gainDb) ? 0.08 : 0.75
        let smoothing = 1.0 - exp(-timeSeconds / timeConstant)
        gainDb += (desiredGainDb - gainDb) * smoothing
        
        let gate: Float = (envelope >= gateThreshold) ? 1.0 : max(0.35, min(1.0, envelope / gateThreshold))
        let gained = sample * gate * pow(10.0, gainDb / 20.0)
        
        return max(-0.98, min(0.98, tanh(gained / 0.98) * 0.98))
    }
    
    private func addEnergy(value: Float) {
        if energyCount == windowSamples {
            energySum -= Double(energy[energyIndex])
        } else {
            energyCount += 1
        }
        energy[energyIndex] = value
        energySum += Double(value)
        energyIndex = (energyIndex + 1) % windowSamples
    }
}

class DialogueAudioProcessor {
    let targetLufs: Float
    let cleanupStrength: Float
    
    // Tap context wrapper
    class TapContext {
        let levelerL: DialogueLeveler
        let levelerR: DialogueLeveler
        
        init(targetLufs: Float, cleanupStrength: Float, sampleRate: Int) {
            self.levelerL = DialogueLeveler(targetLufs: targetLufs, cleanupStrength: cleanupStrength, sampleRate: sampleRate)
            self.levelerR = DialogueLeveler(targetLufs: targetLufs, cleanupStrength: cleanupStrength, sampleRate: sampleRate)
        }
    }
    
    init(cleanupStrength: Float, targetLufs: Float) {
        self.cleanupStrength = cleanupStrength
        self.targetLufs = targetLufs
    }
    
    func createAudioTap() -> MTAudioProcessingTap? {
        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: Unmanaged.passRetained(self).toOpaque(),
            init: { tap, clientInfo, tapStorageOut in
                let processor = Unmanaged<DialogueAudioProcessor>.fromOpaque(clientInfo!).takeUnretainedValue()
                // Use a default sample rate for init, it gets updated in prepare
                let context = TapContext(targetLufs: processor.targetLufs, cleanupStrength: processor.cleanupStrength, sampleRate: 48000)
                tapStorageOut.pointee = Unmanaged.passRetained(context).toOpaque()
            },
            finalize: { tap in
                let storage = MTAudioProcessingTapGetStorage(tap)
                Unmanaged<TapContext>.fromOpaque(storage).release()
            },
            prepare: { tap, maxFrames, processingFormat in
                let storage = MTAudioProcessingTapGetStorage(tap)
                let _ = Unmanaged<TapContext>.fromOpaque(storage).takeUnretainedValue()
                // Ideally update sampleRate here if necessary
            },
            unprepare: { tap in },
            process: { tap, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut in
                let storage = MTAudioProcessingTapGetStorage(tap)
                let context = Unmanaged<TapContext>.fromOpaque(storage).takeUnretainedValue()
                
                let status = MTAudioProcessingTapGetSourceAudio(tap, numberFrames, bufferListInOut, flagsOut, nil, numberFramesOut)
                guard status == noErr else { return }
                
                let bufferList = bufferListInOut.pointee
                guard let mBuffers = bufferList.mBuffers.mData else { return }
                
                let ablPointer = UnsafeMutableAudioBufferListPointer(bufferListInOut)
                
                if ablPointer.count == 2 {
                    let leftData = ablPointer[0].mData?.assumingMemoryBound(to: Float.self)
                    let rightData = ablPointer[1].mData?.assumingMemoryBound(to: Float.self)
                    
                    if let leftData = leftData, let rightData = rightData {
                        for i in 0..<Int(numberFrames) {
                            leftData[i] = context.levelerL.process(sample: leftData[i])
                            rightData[i] = context.levelerR.process(sample: rightData[i])
                        }
                    }
                } else if ablPointer.count == 1 {
                    // Interleaved stereo or mono
                    if let data = ablPointer[0].mData?.assumingMemoryBound(to: Float.self) {
                        let numChannels = Int(ablPointer[0].mNumberChannels)
                        for i in 0..<Int(numberFrames) {
                            for c in 0..<numChannels {
                                let idx = i * numChannels + c
                                if c == 0 {
                                    data[idx] = context.levelerL.process(sample: data[idx])
                                } else {
                                    data[idx] = context.levelerR.process(sample: data[idx])
                                }
                            }
                        }
                    }
                }
            }
        )
        
        var tap: MTAudioProcessingTap?
        let err = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap)
        if err == noErr {
            return tap
        }
        return nil
    }
}
