## 1. Phase deferral for animations (skip Composition to Draw and Layout)

When animating visual properties (such as alpha, scale, rotation, color, or
translation), never read the animated state directly in the Composable body
during the Composition phase. Reading state during composition causes the entire
composable tree to recompose on every animation tick (60 to 120 times per
second).

Wrap animated state reads in lambdas so evaluation is deferred to the **Draw**
or
**Layout** phase.

### A. Alpha, rotation, and scale: defer to Draw with `graphicsLayer`

- **Bad (Recomposes on every animation frame)**:


  ```kotlin
  val alpha by animateFloatAsState(targetValue = targetAlpha, label = "Alpha")
  // Reading alpha here registers a composition dependency on every frame
  Box(modifier = Modifier.alpha(alpha))
  ```

  <br />

- **Optimized (Bypasses Composition and Layout, evaluates in Draw phase)**:


  ```kotlin
  val alpha by animateFloatAsState(targetValue = targetAlpha, label = "Alpha")
  // graphicsLayer with lambda skips composition and runs in the draw phase
  Box(
      modifier = Modifier.graphicsLayer {
          this.alpha = alpha
      }
  )
  ```

  <br />

### B. Animated offsets: defer to Layout with `Modifier.offset`

- **Bad (Recomposes on every pixel shift)**:


  ```kotlin
  val animatedOffset by animateDpAsState(
      targetValue = targetOffset,
      label = "Offset",
  )
  Box(modifier = Modifier.offset(x = animatedOffset, y = 0.dp))
  ```

  <br />

- **Optimized (Skips Composition, goes straight to Layout phase)**:


  ```kotlin
  val animatedOffset by animateIntAsState(
      targetValue = targetOffsetPx,
      label = "Offset",
  )
  Box(
      modifier = Modifier.offset {
          IntOffset(x = animatedOffset, y = 0)
      }
  )
  ```

  <br />

### C. Animated size: defer to Layout with `Modifier.layout` or `animateBounds`

Animating container dimensions using `animateDpAsState` and `Modifier.size(...)`
directly in the Composable body triggers recomposition of the composable and its
children on every animation tick.

Use layout-phase constraint animation or Lookahead bounds animation instead:

- **Bad (Recomposes entire subtree on every animation frame)**:


  ```kotlin
  val animatedSize by animateDpAsState(
      targetValue = targetSize,
      label = "Size",
  )
  Box(modifier = Modifier.size(animatedSize))
  ```

  <br />

- **Optimized (Phase Deferral with `Modifier.layout`)**: Animate constraints
  in the layout pass without re-triggering composition:


  ```kotlin
  val animatedWidth by animateIntAsState(
      targetValue = targetWidthPx,
      label = "Width",
  )
  Box(
      modifier = Modifier.layout { measurable, constraints ->
          val placeable = measurable.measure(
              constraints.copy(
                  minWidth = animatedWidth,
                  maxWidth = animatedWidth
              )
          )
          layout(placeable.width, placeable.height) {
              placeable.placeRelative(0, 0)
          }
      }
  )
  ```

  <br />

- **Optimized (Lookahead Scope with `animateBounds`)** : Inside a
  `LookaheadScope`, animate size changes smoothly using `animateBounds`:


  ```kotlin
  with(lookaheadScope) {
      Box(modifier = Modifier.animateBounds(this, Modifier.size(finalSize)))
  }
  ```

  <br />

### D. Custom composable parameters (lambda providers)

When creating custom composables that animate properties internally, pass a
lambda provider (`alphaProvider: () -> Float` or `offsetProvider: () ->
IntOffset`) instead of the raw animated value. This allows the caller to avoid
reading the state in Composition and allows the child to defer the read to
`graphicsLayer` or `offset`.

- **Bad (Caller must read state in Composition to pass Float)**:


  ```kotlin
  @Composable
  fun AnimatedCard(alpha: Float) {
      Box(modifier = Modifier.graphicsLayer { this.alpha = alpha })
  }
  ```

  <br />

- **Optimized (Caller passes lambda; read is deferred until child's Draw
  phase)**:


  ```kotlin
  @Composable
  fun AnimatedCard(alphaProvider: () -> Float) {
      Box(modifier = Modifier.graphicsLayer { this.alpha = alphaProvider() })
  }
  ```

  <br />

## 2. Shared transitions and shared elements (`SharedTransitionScope`)

`SharedTransitionScope` enables coordinated bounds and element transitions
across layouts and navigation destinations.

### 1. Defer `sharedBounds` and `sharedElement` in fast-scrolling lists

**Recommendation:** For long or fast-scrolling lists (`LazyColumn`, `LazyRow`,
`LazyVerticalGrid`, `HorizontalPager`), consider attaching
`Modifier.sharedBounds` or `Modifier.sharedElement` only when items enter the
visible viewport using `Modifier.onVisibilityChanged`.

While spatial coordinate tracking is only enabled when a match is found,
unconditionally attaching shared transition modifiers introduces modifier
instantiation and state observation overhead for **pre-fetched items that may
never end up on screen** during fast scroll flings. Deferring attachment until
visible avoids this instantiation cost for offscreen items.
> **Note:**
> This recommendation applies to current versions of Compose. Upstream platform
> performance optimizations to make `Modifier.sharedBounds` and
> `Modifier.sharedElement` lighter-weight and avoid prefetch overhead are in
> active development.

- **Before (Instantiation runs on all pre-fetched items even if never
  displayed)**:


  ```kotlin
  @Composable
  fun FeedItemRow(
      item: FeedItem,
      sharedTransitionScope: SharedTransitionScope,
      animatedVisibilityScope: AnimatedVisibilityScope,
  ) {
      with(sharedTransitionScope) {
          ItemContent(
              modifier = Modifier
                  .fillMaxWidth()
                  .sharedBounds(
                      rememberSharedContentState(key = item.id),
                      animatedVisibilityScope = animatedVisibilityScope,
                  )
          )
      }
  }
  ```

  <br />

- **Optimized (Shared transition modifier deferred until item enters
  viewport)**:


  ```kotlin
  @Composable
  fun FeedItemRow(
      item: FeedItem,
      sharedTransitionScope: SharedTransitionScope,
      animatedVisibilityScope: AnimatedVisibilityScope,
  ) {
      var isVisibleInWindow by remember { mutableStateOf(false) }

      with(sharedTransitionScope) {
          val sharedBoundsModifier = if (isVisibleInWindow) {
              Modifier.sharedBounds(
                  rememberSharedContentState(key = item.id),
                  animatedVisibilityScope = animatedVisibilityScope,
              )
          } else {
              Modifier
          }

          ItemContent(
              modifier = Modifier
                  .fillMaxWidth()
                  .onVisibilityChanged(
                      minFractionVisible = 0.001f,
                  ) { visible ->
                      isVisibleInWindow = visible
                  }
                  .then(sharedBoundsModifier)
          )
      }
  }
  ```

  <br />

## 3. Persistent `Animatable` in custom modifiers (`Modifier.Node`)

When building custom animated modifiers, do not use `composed { ... }` with
`remember { Animatable(...) }` and `LaunchedEffect`.

Store `Animatable` directly as a member property on the `Modifier.Node` and
launch the animation in `onAttach()` using the node's built-in `coroutineScope`:


```kotlin
private class FadeRevealNode(
    var targetAlpha: Float,
    var durationMillis: Int
) : Modifier.Node(), DrawModifierNode {
    // Persistent node property (avoids remember overhead)
    private val alpha = Animatable(0f)

    override fun onAttach() {
        // Launches animation tied directly to node lifecycle
        coroutineScope.launch {
            alpha.animateTo(targetAlpha, tween(durationMillis))
        }
    }

    override fun ContentDrawScope.draw() {
        drawContext.canvas.saveLayer(
            bounds = size.toRect(),
            paint = Paint().apply {
                this.alpha = this@FadeRevealNode.alpha.value
            }
        )
        drawContent()
        drawContext.canvas.restore()
    }
}
```

<br />