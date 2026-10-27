# Audio fixtures

`tone.ogg` is the existing short synthetic regression tone. The three
`vorbis-<sample-rate>-<channels>.ogg` files are newly generated 440 Hz sine tones,
0.12 seconds long, encoded with FFmpeg's libvorbis encoder. They cover 8 kHz mono
(quality -1), 48 kHz stereo (quality 5), and 96 kHz stereo (quality 8).

Tests load these files directly; FFmpeg is not required to run the plugin or
the regression suite. Malformed samples are constructed in memory by the tests
and are not shipped as playback assets.

The setup reader follows the [Xiph Vorbis I specification](https://xiph.org/vorbis/doc/Vorbis_I_spec.html).
