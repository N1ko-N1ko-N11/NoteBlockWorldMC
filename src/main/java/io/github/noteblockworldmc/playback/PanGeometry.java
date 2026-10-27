package io.github.noteblockworldmc.playback;

/**
 * Where to place a panned note around the listener.
 * <p>
 * Minecraft has no pan control; a positional sound is panned by the client
 * from the DIRECTION it arrives from, relative to the camera. Distance only
 * changes loudness, so a note placed directly to the side pans fully however
 * close it is. A pan value p is therefore reproduced by placing the note at
 * the azimuth whose sine is p, at a fixed radius around the camera.
 */
final class PanGeometry {
    private PanGeometry() {}

    /** Camera look direction for a yaw/pitch, matching the client's listener orientation. */
    static double[] forward(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
        return new double[]{-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad)};
    }

    /**
     * The listener's right, from yaw alone: horizontal (the camera never
     * rolls) and always unit length, even when looking straight up or down.
     */
    static double[] right(float yaw) {
        double yawRad = Math.toRadians(yaw);
        return new double[]{-Math.cos(yawRad), 0, -Math.sin(yawRad)};
    }

    /** Offset at {@code radius} whose direction gives sin(azimuth) = pan. */
    static double[] offset(double pan, double radius, double[] forward, double[] right) {
        double sin = Math.clamp(pan, -1, 1), cos = Math.sqrt(1 - sin * sin);
        return new double[]{radius * (cos * forward[0] + sin * right[0]),
                radius * (cos * forward[1] + sin * right[1]),
                radius * (cos * forward[2] + sin * right[2])};
    }
}
