gstreamer-publisher --token "$LIVEKIT_PUBLISH_TOKEN" -- \
  ximagesrc use-damage=0 show-pointer=true ! \
    video/x-raw,framerate=30/1 ! \
    videoconvert ! \
    x264enc tune=zerolatency bitrate=1500 speed-preset=superfast key-int-max=60 ! \
    video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline ! \
    h264parse config-interval=1
