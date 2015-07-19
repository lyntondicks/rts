Unfinished project, but you can see it [here](http://alexis.lart.no/emh/rts.git/).

# Resources
 - [Physijs](http://chandlerprall.github.io/Physijs/)
 - [Three.js](http://threejs.org/)
 - [Three.js model editor](http://threejs.org/editor/) Can import .obj and export to .json.
 - [Turbosquid 3D Models](http://www.turbosquid.com)

# TODO

## Code
 - Separate game logic from rendering

## Functionality
 - Constrain camera controls to stay on map
 - Pathfinding / A-star
 - Minimap
   - Add terrain
   - Units as colored squares with black border
   - Link minimap camera rotation to screen camera rotation?
 - Disable unit selection when mouse control for camera is enabled
 - Configurable (or at least listable) keyboard controls
 - Export/import terrain as JSON
 - Terraforming (like [Worldmonger](http://www.babylonjs.com/Scenes/Worldmonger/index.html))
 - Select more than unit with ctrl or shift
 - Move selected unit according to its move speed
  - Target
  - Waypoint queue
 - Attack another unit
  - Select target unit
 - Show actions specific to selected object
 - Cursor indicator when camera enabled?
 - Add ocean floor at edge of map
 - 3D positioning of sounds

## Server needed
 - Multiplayer
  - Store game state on server
  - Synchronization
