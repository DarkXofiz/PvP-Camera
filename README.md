# PvP Camera (Minecraft 1.20.1 · Fabric)

**Gives a fighting game style, side on view of 2 players. Fully compatible with Flashback replays**

![](https://github.com/TheobaldTheBird/Fight-Camera/blob/a3cce29a1898a8b7c0575a38b6bceac3c5c4b889/fightcam.gif)

# Video recording

* Press **'** (apostrophe, rebindable under *PvP Camera* in Controls) to start recording, press again to stop.
* Recording stops by itself after **10 minutes** and "Kayıt sona erdi" is shown at the top of the screen.
* In the pause menu there is a small red square button on the right. It opens the list of recordings.
  Select one and press **İndir** (bottom right) to copy it into the gallery (`Movies/PvPCamera` on Android, `Videos/PvP Camera` on PC).
* Recordings are saved as `.avi` (MJPEG, max 854px wide, 15 fps) in `.minecraft/pvp-camera/recordings`.

# Own-fight mode (default)

Press `;` (or `/fightcam self`) and the camera automatically follows **you and the nearest player** (within 12 blocks)
from the side, with smooth animation. It switches back to your normal view when nobody is close.
Your own mouse look / movement stay under your control. Use `/fightcam players <p1> <p2>` to watch two specific players instead.

# Usage:
(`/pvpcam` works as a shorter alias of `/fightcam`)


/fightcam players <player1> <player2> to set the camera's target players

/fightcam toggle to toggle fightcam (bound to ; by default)

/fightcam smooth <factor> to set the lerp factor for the camera. Values closer to 0 give smoother camera movement, values closer to 1 give more reactive camera movment. Defaults to 0.6
### Camera distance

/fightcam distance <distance> set the camera distance to the anchor position in blocks (changes camera mode to static)

/fightcam distance auto makes it so the camera adjusts the distance automatically to keep the players in view (base distance is adjustable with forward and backward keys)
### Camera Height

/fightcam height <height> sets the camera's height relative to the anchor position in blocks

/fightcam height avg sets the camera to follow the average height of the players (good for viewing mace and crystal fights with constant height changes)

/fightcam height ground sets the camera to follow the average height of the last grounded height of the players (good for gamemodes with little height change/flat terrain)
### Camera Anchor

/fightcam anchor avg sets the camera to follow the average position of both players

/fightcam anchor <p1/p2> sets the camera to follow the first or second player (automatically sets distance to be fixed)
