import {Pose} from '@mediapipe/pose';
import RNFS from 'react-native-fs';

export class PoseProcessor {
  private pose: Pose;

  constructor() {
    this.pose = new Pose({
      locateFile: file => {
        return `https://cdn.jsdelivr.net/npm/@mediapipe/pose/${file}`;
      },
    });

    this.pose.setOptions({
      modelComplexity: 1,
      smoothLandmarks: true,
      enableSegmentation: false,
      smoothSegmentation: false,
      minDetectionConfidence: 0.5,
      minTrackingConfidence: 0.5,
    });
  }

  async initialize() {
    await this.pose.initialize();
  }

  async processVideoFrame(
    frame: HTMLVideoElement | HTMLImageElement,
  ): Promise<any> {
    const results = await this.pose.send({image: frame});
    return results;
  }

  // Helper function to extract frame from video at a specific time
  async extractFrameFromVideo(
    videoUri: string,
    timestamp: number,
  ): Promise<HTMLImageElement> {
    return new Promise((resolve, reject) => {
      const video = document.createElement('video');
      video.src = videoUri;
      video.currentTime = timestamp;

      video.onloadeddata = () => {
        const canvas = document.createElement('canvas');
        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;

        const ctx = canvas.getContext('2d');
        if (ctx) {
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

          const img = new Image();
          img.src = canvas.toDataURL();
          img.onload = () => resolve(img);
          img.onerror = reject;
        } else {
          reject(new Error('Could not get canvas context'));
        }
      };

      video.onerror = reject;
    });
  }

  // Process the entire video and extract landmarks
  async processVideo(videoUri: string, frameRate: number = 10): Promise<any[]> {
    const video = document.createElement('video');
    video.src = videoUri;

    return new Promise((resolve, reject) => {
      video.onloadedmetadata = async () => {
        const duration = video.duration;
        const frameInterval = 1 / frameRate;
        const results = [];

        try {
          for (let time = 0; time < duration; time += frameInterval) {
            const frame = await this.extractFrameFromVideo(videoUri, time);
            const poseResults = await this.processVideoFrame(frame);

            if (poseResults.poseLandmarks) {
              results.push({
                timestamp: time,
                landmarks: poseResults.poseLandmarks,
              });
            }
          }

          resolve(results);
        } catch (error) {
          reject(error);
        }
      };

      video.onerror = reject;
    });
  }
}
