gstreamer-publisher --token "$LIVEKIT_PUBLISH_TOKEN" -- \
  avfvideosrc capture-screen=true capture-screen-cursor=true do-timestamp=true \
    ! videoconvert \
    ! x264enc tune=zerolatency bitrate=1500 speed-preset=superfast key-int-max=60 \
    ! video/x-h264,stream-format=byte-stream,alignment=au,profile=baseline \
    ! h264parse config-interval=1
