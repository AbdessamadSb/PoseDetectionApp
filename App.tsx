import React, {useState, useEffect, useRef} from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  ScrollView,
  Alert,
  Image,
  Dimensions,
  StatusBar,
} from 'react-native';
import {launchImageLibrary} from 'react-native-image-picker';
import MediaPipeModule from './MediaPipeModule';

const App = () => {
  const [videoUri, setVideoUri] = useState<string | null>(null);
  const [frameData, setFrameData] = useState<any[]>([]);
  const [processing, setProcessing] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [currentFrameIndex, setCurrentFrameIndex] = useState(0);
  const playbackInterval = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    // Initialize MediaPipe when the component mounts
    const initializeMediaPipe = async () => {
      try {
        const result = await MediaPipeModule.initialize();
        console.log(result);
      } catch (error) {
        console.error('Failed to initialize MediaPipe:', error);
        Alert.alert('Error', 'Failed to initialize pose detection');
      }
    };

    initializeMediaPipe();
  }, []);

  useEffect(() => {
    // Cleanup interval on unmount
    return () => {
      if (playbackInterval.current) {
        clearInterval(playbackInterval.current);
      }
    };
  }, []);

  const handleVideoUpload = async () => {
    try {
      const result = await launchImageLibrary({
        mediaType: 'video',
        quality: 1,
      });

      if (result.assets && result.assets[0]) {
        const video = result.assets[0];
        setVideoUri(video.uri || null);

        // Process the video
        processVideo(video.uri || '');
      }
    } catch (error) {
      Alert.alert('Error', 'Failed to upload video');
      console.error(error);
    }
  };

  const processVideo = async (uri: string) => {
    setProcessing(true);
    try {
      // Process the video using native MediaPipe module
      const results = await MediaPipeModule.processVideo(uri);

      if (results && results.length > 0) {
        setFrameData(results);
        setCurrentFrameIndex(0);
        Alert.alert(
          'Success',
          `Processed ${results.length} frames with pose landmarks!`,
        );
      } else {
        Alert.alert('No Poses', 'No poses were detected in the video');
      }
    } catch (error) {
      Alert.alert('Error', 'Failed to process video');
      console.error(error);
    } finally {
      setProcessing(false);
    }
  };

  const togglePlayback = () => {
    if (playing) {
      // Stop playback
      if (playbackInterval.current) {
        clearInterval(playbackInterval.current);
        playbackInterval.current = null;
      }
      setPlaying(false);
    } else {
      // Start playback
      setPlaying(true);
      playbackInterval.current = setInterval(() => {
        setCurrentFrameIndex(prevIndex => {
          if (prevIndex >= frameData.length - 1) {
            // Reached the end, stop playback
            if (playbackInterval.current) {
              clearInterval(playbackInterval.current);
              playbackInterval.current = null;
            }
            setPlaying(false);
            return 0;
          }
          return prevIndex + 1;
        });
      }, 100); // 10 FPS playback
    }
  };

  const renderLandmarkDots = (
    landmarks: any[],
    frameWidth: number,
    frameHeight: number,
  ) => {
    return landmarks.map((landmark, index) => {
      const x = landmark.x * frameWidth;
      const y = landmark.y * frameHeight;

      return (
        <View
          key={index}
          style={[
            styles.landmarkDot,
            {
              left: x - 5,
              top: y - 5,
              backgroundColor: getLandmarkColor(landmark.name),
            },
          ]}
        />
      );
    });
  };

  const getLandmarkColor = (name: string) => {
    if (name.includes('LEFT')) return '#FF0000';
    if (name.includes('RIGHT')) return '#0000FF';
    if (name.includes('NOSE') || name.includes('EYE') || name.includes('EAR'))
      return '#00FF00';
    return '#FFFF00';
  };

  const renderVideoPlayer = () => {
    if (!frameData.length) return null;

    const screenWidth = Dimensions.get('window').width;
    const frameHeight = (screenWidth * 9) / 16; // Assuming 16:9 aspect ratio
    const currentFrame = frameData[currentFrameIndex];

    return (
      <View style={styles.videoPlayerContainer}>
        <View style={styles.videoPlayer}>
          {currentFrame.frameImage ? (
            <Image
              source={{uri: currentFrame.frameImage}}
              style={[
                styles.videoFrame,
                {width: screenWidth, height: frameHeight},
              ]}
              resizeMode="cover"
            />
          ) : (
            <View
              style={[
                styles.placeholderFrame,
                {width: screenWidth, height: frameHeight},
              ]}>
              <Text style={styles.placeholderText}>
                Frame {currentFrameIndex + 1}
              </Text>
            </View>
          )}
          {renderLandmarkDots(currentFrame.landmarks, screenWidth, frameHeight)}
        </View>

        <View style={styles.videoControls}>
          <TouchableOpacity style={styles.playButton} onPress={togglePlayback}>
            <Text style={styles.playButtonText}>
              {playing ? 'Pause' : 'Play'}
            </Text>
          </TouchableOpacity>

          <Text style={styles.frameInfo}>
            Frame: {currentFrameIndex + 1}/{frameData.length} | Time:{' '}
            {(currentFrame.timestamp / 1000).toFixed(2)}s
          </Text>
        </View>

        <View style={styles.progressBarContainer}>
          <View
            style={[
              styles.progressBar,
              {width: `${((currentFrameIndex + 1) / frameData.length) * 100}%`},
            ]}
          />
        </View>
      </View>
    );
  };

  const renderFrameThumbnails = () => {
    return (
      <ScrollView
        horizontal
        style={styles.thumbnailsScrollView}
        showsHorizontalScrollIndicator={false}>
        {frameData.map((frame, index) => (
          <TouchableOpacity
            key={index}
            style={[
              styles.thumbnailContainer,
              currentFrameIndex === index && styles.selectedThumbnail,
            ]}
            onPress={() => {
              setCurrentFrameIndex(index);
              if (playing) {
                // Stop playback if playing
                if (playbackInterval.current) {
                  clearInterval(playbackInterval.current);
                  playbackInterval.current = null;
                }
                setPlaying(false);
              }
            }}>
            <Image
              source={{uri: frame.frameImage}}
              style={styles.thumbnailImage}
              resizeMode="cover"
            />
            <Text style={styles.thumbnailTimestamp}>
              {(frame.timestamp / 1000).toFixed(1)}s
            </Text>
          </TouchableOpacity>
        ))}
      </ScrollView>
    );
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.content}>
        {frameData.length === 0 && (
          <View style={styles.headerContainer}>
            <Text style={styles.title}>Pose Detection</Text>
            <TouchableOpacity
              style={[styles.uploadButton, processing && styles.buttonDisabled]}
              onPress={handleVideoUpload}
              disabled={processing}>
              <Text style={styles.buttonText}>
                {processing ? 'Processing...' : 'Upload Video'}
              </Text>
            </TouchableOpacity>
          </View>
        )}

        {frameData.length > 0 && (
          <View style={styles.resultsContainer}>
            {renderVideoPlayer()}
            {renderFrameThumbnails()}
          </View>
        )}
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
  content: {
    flex: 1,
  },
  headerContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 30,
    color: '#333',
  },
  uploadButton: {
    backgroundColor: '#007AFF',
    paddingVertical: 15,
    paddingHorizontal: 30,
    borderRadius: 10,
    alignItems: 'center',
    minWidth: 200,
  },
  buttonDisabled: {
    backgroundColor: '#999',
  },
  buttonText: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
  },
  resultsContainer: {
    flex: 1,
  },
  videoPlayerContainer: {
    flex: 1,
  },
  videoPlayer: {
    position: 'relative',
    backgroundColor: '#000',
  },
  videoFrame: {
    backgroundColor: '#000',
  },
  placeholderFrame: {
    backgroundColor: '#f0f0f0',
    justifyContent: 'center',
    alignItems: 'center',
  },
  placeholderText: {
    color: '#666',
    fontSize: 16,
  },
  videoControls: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 10,
    backgroundColor: '#f8f8f8',
  },
  playButton: {
    backgroundColor: '#007AFF',
    paddingVertical: 8,
    paddingHorizontal: 20,
    borderRadius: 5,
  },
  playButtonText: {
    color: '#fff',
    fontWeight: 'bold',
  },
  frameInfo: {
    fontSize: 14,
    color: '#666',
  },
  progressBarContainer: {
    height: 3,
    backgroundColor: '#e0e0e0',
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#007AFF',
  },
  thumbnailsScrollView: {
    maxHeight: 60,
    backgroundColor: '#f8f8f8',
  },
  thumbnailContainer: {
    width: 60,
    height: 45,
    margin: 5,
    borderWidth: 2,
    borderColor: 'transparent',
    borderRadius: 5,
    overflow: 'hidden',
  },
  selectedThumbnail: {
    borderColor: '#007AFF',
  },
  thumbnailImage: {
    width: '100%',
    height: '100%',
  },
  thumbnailTimestamp: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
    color: '#fff',
    fontSize: 9,
    paddingHorizontal: 3,
    paddingVertical: 1,
  },
  landmarkDot: {
    position: 'absolute',
    width: 10,
    height: 10,
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#fff',
  },
});

export default App;