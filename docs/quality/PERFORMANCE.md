# Performance
Measure before optimizing. Initial UI avoids GPS/realtime state, uses responsive image sizes, stable layouts and restrained motion.
Target immediate interaction feedback and approximately 60 FPS, verified later on representative Android hardware. No numerical performance claim without measurement.
Use bounded lists and isolate future map/presence state. Do not rerender an entire app per location update.

