# Photogrammetry (Android)

AR capture → on-device SfM (with native Ceres bundle adjustment) → point cloud /
COLMAP export → PC dense mesh → GL mesh viewer → AR drop-in.

## Workflow
1. New scan → orbit object slowly (Auto capture ON recommended)
2. Scan list → tap scan → Reconstruct (background WorkManager job w/ notification)
3. View cloud (on-device GL viewer), Share PLY, or export COLMAP sparse
4. PC dense mesh:
   colmap point_triangulator --input_path colmap_sparse ...
   colmap patch_match_stereo && colmap stereo_fusion
   MeshLab → Poisson → export .glb
5. adb push model.glb /sdcard/Android/data/com.example.photogrammetry/files/models/
6. Mesh viewer → Open / AR

## Requirements
- Android Studio with JDK 17, NDK + CMake 3.22.1 (SDK Manager)
- Physical ARCore-supported device
- First native build fetches Eigen + Ceres (~10 min, one time)

## Notes
- SceneView 2.2.1 API drift: if composable signatures differ in your version,
  follow that version's README — the flow (engine→loader→cameraNode→childNodes)
  is stable across 2.x.
- If captures appear sideways on a rare device, change CAPTURE_ROTATION_DEG
  in ScanActivity (90/180/270 — intrinsics rotate along).
