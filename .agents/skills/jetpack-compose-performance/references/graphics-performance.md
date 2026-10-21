## 1. Custom drawing (`Canvas` versus `drawWithCache`)

- **Avoid Nested Canvas Nodes** : Avoid using the `Canvas` composable solely to add decorative drawings to an existing layout. Instead, use `Modifier.drawBehind` or `Modifier.drawWithCache` on the existing Composable to avoid creating an extra node in the layout tree.
- **Cache Allocations with `drawWithCache`** : If your drawing logic allocates objects that depend on the size of the drawing area (like `Path`, `Brush`, `Shader`, or `Paint`), use **`Modifier.drawWithCache`** to cache allocated objects and avoid GC pressure during the draw phase.

### A. Caching `Path` allocations

- **Bad (Allocates a new Path on every single draw frame or animation tick)**:


  ```kotlin
  Box(
      modifier = Modifier
          .fillMaxSize()
          .drawBehind {
              val path = Path().apply {
                  moveTo(0f, 0f)
                  lineTo(size.width, size.height)
              }
              drawPath(path, Color.Red)
          }
  )
  ```

  <br />

- **Optimized (Caches the Path, only recreates it if the Box size changes)**:


  ```kotlin
  Box(
      modifier = Modifier
          .fillMaxSize()
          .drawWithCache {
              val path = Path().apply {
                  moveTo(0f, 0f)
                  lineTo(size.width, size.height)
              }
              onDrawBehind {
                  drawPath(path, Color.Red)
              }
          }
  )
  ```

  <br />

## 2. Scope fast-changing uniforms inside `drawWithCache`

- **Remember the `RuntimeShader`** : Always `remember` the `RuntimeShader` (or lazily initialize it once at top-level / process scope) outside of `drawWithCache`. Although compiled shaders are cached internally by the runtime, parsing the AGSL shader source string incurs significant parsing overhead and should never be repeated inside `drawWithCache` size-change passes.
- **Remember the `ShaderBrush`** : `ShaderBrush` is a lightweight wrapper holding a reference to the shader. Remember it keyed on the shader: `remember(shader) { ShaderBrush(shader) }`.
- **Update layout- and size-dependent uniforms** (like viewport size or resolution using `size.width, size.height`) in the `drawWithCache` initialization block, as size changes only during layout passes.
- **Move dynamic, per-frame uniform updates** (such as updating time using `shader.setFloatUniform(...)`) inside the `onDrawBehind` or `onDrawWithContent` block without reallocating the shader or brush:


```kotlin
// RuntimeShader parsed once and cached in Composable scope
val shader = remember { RuntimeShader(SHADER_SRC) }
val brush = remember(shader) { ShaderBrush(shader) }

Box(
    modifier = modifier
        .fillMaxSize()
        .drawWithCache {
            // Layout/size-dependent uniforms updated in cache block (on resize)
            shader.setFloatUniform("u_resolution", size.width, size.height)

            onDrawBehind {
                // Per-frame uniforms updated in draw block without reallocating
                shader.setFloatUniform("u_time", timeState.value)
                drawRect(brush)
            }
        }
)
```

<br />

## 3. Graphics `Path` performance and object reuse

- **Prefer `Path.rewind()` over `Path.reset()` in Hot Draw Loops** :
  - **`rewind()`** : Clears lines and curves but **retains internal buffers
    and allocated capacity** in memory. Use `rewind()` when rebuilding a path on every frame with stable complexity (such as waveform monitors or custom charts) to avoid buffer reallocations.
  - **`reset()`** : Clears the path and **releases internal buffers** . Use `reset()` only when the path will not be reused soon or when its complexity is shrinking sharply.

## 4. Value classes, primitive collections, and packing

Avoid allocating short-lived objects (such as `Pair<Int, Int>`, `Pair<Float,
Float>`, `Point`, coordinate pairs, or dimensions) inside loops, animation
callbacks, measure blocks, or draw methods.

- **Avoid Generic Tuples (`Pair`, `Triple`)** : Allocating `Pair(row, col)` or `Pair(x, y)` in hot loops creates continuous heap allocations and GC pressure (e.g. `HashSet<Pair<Int, Int>>` allocates the `Pair` object, boxes both primitive `Int`s, and creates a `HashMap.Node` entry --- totaling 3+ heap allocations per element).
- **Standard Generic Sets Box Primitives** : `HashSet<Long>` or `HashSet<Int>` boxes every primitive to `java.lang.Long`/`java.lang.Integer` and allocates a `HashMap.Node`. For true zero-allocation collections, use **`androidx.collection.MutableLongSet`** or **`MutableIntSet`** from `androidx.collection:collection`.
- **Avoid Iterator Allocations in Loops** : `for (item in items)` allocates an `Iterator` object when `items` is a standard `List`. Use indexed loops (`for
  (i in items.indices)`) or `items.fastForEach { ... }` from `androidx.compose.ui.util`.
- **Value Class Boxing Traps** : Kotlin `@JvmInline value class` and primitive types box into heap objects when used as generic type arguments (e.g. `Set<Point>`), when declared as nullable (`Point?`), or when cast to `Any`/interface types.
- **Use Built-in Compose Packing Utilities** : Prefer the built-in primitive packing functions in `androidx.compose.ui.util` (`packInts`, `unpackInt1`, `unpackInt2`, `packFloats`, `unpackFloat1`, `unpackFloat2`) or Compose value classes (`IntOffset`, `Offset`, `IntSize`, `Size`) instead of custom bitwise operators.

### A. Primitive packing with `MutableLongSet` (grid or layout calculations)

- **Bad (Allocates Pair, boxed Ints, iterator, and HashMap.Node per
  element)**:


  ```kotlin
  val occupied = HashSet<Pair<Int, Int>>()
  for (item in items) {
      occupied.add(Pair(item.row, item.col))
  }
  ```

  <br />

- **Optimized (0 heap allocations using MutableLongSet, packInts, and
  fastForEach)**:


  ```kotlin
  // import androidx.collection.MutableLongSet
  // import androidx.compose.ui.util.fastForEach
  // import androidx.compose.ui.util.packInts

  val occupied = MutableLongSet()
  items.fastForEach { item ->
      occupied.add(packInts(item.row, item.col))
  }
  ```

  <br />

### B. Value class for coordinates

- **Bad (Creates heap Point objects on every iteration)**:


  ```kotlin
  class Point(val x: Float, val y: Float)
  var acc = Point(0f, 0f)
  for (i in 0 until 1000) {
      acc = Point(acc.x + i, acc.y - i)
  }
  ```

  <br />

- **Optimized (0 heap allocations, packs 2 floats into a Long primitive value
  class)**:


  ```kotlin
  // import androidx.compose.ui.util.packFloats
  // import androidx.compose.ui.util.unpackFloat1
  // import androidx.compose.ui.util.unpackFloat2

  @JvmInline
  value class Point private constructor(val packedValue: Long) {
      constructor(x: Float, y: Float) : this(packFloats(x, y))

      val x: Float get() = unpackFloat1(packedValue)
      val y: Float get() = unpackFloat2(packedValue)
  }

  var acc = Point(0f, 0f)
  for (i in 0 until 1000) {
      acc = Point(acc.x + i, acc.y - i)
  }
  // Alternatively, use Compose's built-in Offset class, which implements
  // this exact @JvmInline Long packing pattern under the hood.
  ```

  <br />

## 5. Image loading and decoding (`AsyncImage` versus `painterResource`)

Avoid using `Image(painter = painterResource(resId))` to render raster photos or
large bitmap assets---especially inside scrolling lists (`LazyColumn`, `LazyRow`,
`LazyGrid`). Reserve `painterResource` strictly for vector drawables (`xml`) and
small UI icons.

- **Synchronous Main-Thread Decoding** : `painterResource` decodes bitmaps synchronously on the main thread during composition (`Compose:recompose` -\> `ImageDecoder`), including during lazy list prefetch (`lazy:prefetch:compose`). Even a small compressed JPEG in the APK (e.g., 159 KB) can decompress into a large `2760x1840` bitmap, blocking the main thread and causing dropped frames.
- **No Target-Size Downsampling** : `painterResource` has no awareness of the layout size (such as `Modifier.size(160.dp)`) and decodes the image at full original resolution.
- **Prefer Async image loading like Coil's `AsyncImage` (or Asynchronous
  Downsampling)** :
  - **Offloads Decoding** : `AsyncImage` decodes images asynchronously on a background thread, keeping the main thread free during scroll and prefetch.
  - **Automatic Downsampling (`ImageDecoder.setTargetSize`)** : `AsyncImage` measures the target layout bounds and configures `ImageDecoder.setTargetSize()` automatically so only the pixels actually displayed are decoded and uploaded to the GPU.
  - **Ship Appropriately Sized Assets**: If bundling static thumbnails in the APK, ship pre-scaled thumbnail assets matching the display bucket rather than full-resolution camera photos.

### A. Loading bitmap resources or photos in a fixed-size container

- **Bad (Synchronously decodes full `2760x1840` bitmap on the main thread and
  uploads the full texture to the GPU for a `160.dp` box)**:


  ```kotlin
  Image(
      painter = painterResource(id = R.drawable.donut),
      contentScale = ContentScale.Fit,
      modifier = Modifier.size(160.dp),
      contentDescription = stringResource(id = R.string.attached_image),
  )
  ```

  <br />

- **Optimized (Decodes off the main thread, automatically downsamples to the
  `160.dp` target dimensions via `ImageDecoder.setTargetSize`, and caches the
  result)**:


  ```kotlin
  // import coil3.compose.AsyncImage

  AsyncImage(
      model = R.drawable.donut,
      contentScale = ContentScale.Fit,
      modifier = Modifier.size(160.dp),
      contentDescription = stringResource(id = R.string.attached_image),
  )
  ```

  <br />