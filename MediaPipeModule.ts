import { NativeModules, Platform } from 'react-native';

interface IMediaPipeModule {
  initialize(): Promise<string>;
  processVideo(videoUri: string): Promise<any[]>;
}

const { MediaPipeModule } = NativeModules;

// Fallback implementation for iOS or if module is not available
const FallbackMediaPipe: IMediaPipeModule = {
  initialize: async () => {
    console.warn('MediaPipe is not available on this platform');
    return 'Fallback implementation';
  },
  processVideo: async (videoUri: string) => {
    console.warn('MediaPipe is not available on this platform');
    return [];
  },
};

export default (Platform.OS === 'android' && MediaPipeModule) 
  ? MediaPipeModule 
  : FallbackMediaPipe;