## 1. Phase deferral and lambdas (Composition versus Layout versus Draw)

Defer reading animated, frequently changing, or scroll-driven state values until
the Layout or Draw phase by wrapping reads in lambdas. This bypasses the
expensive Composition phase entirely and avoids unnecessary recompositions.

### A. Modifier offsets

- **Bad (Recomposes on every pixel change)**:


  ```kotlin
  val offset = scrollState.value
  Box(modifier = Modifier.offset(x = offset.dp, y = 0.dp))
  ```

  <br />

- **Optimized (Skips Composition, goes straight to Layout)**:


  ```kotlin
  Box(modifier = Modifier.offset { IntOffset(scrollState.value, 0) })
  ```

  <br />

### B. Alpha and rotation animations

- **Bad (Recomposes on every animation frame)**:


  ```kotlin
  val alpha by animateFloatAsState(targetValue)
  Box(modifier = Modifier.alpha(alpha))
  ```

  <br />

- **Optimized (Skips Composition and Layout, goes straight to Draw)**:


  ```kotlin
  val alpha by animateFloatAsState(targetValue)
  Box(modifier = Modifier.graphicsLayer { this.alpha = alpha })
  ```

  <br />

### C. Custom composable parameters

Passing a lambda provider (`valueProvider: () -> Float`) is beneficial only when
the value is read in Layout or Draw. If read in Composition, pass the raw value
directly to avoid lambda allocation overhead.

- **Read in Composition (Pass value directly)**:


  ```kotlin
  @Composable
  fun TitleText(text: String) {
      Text(text = text)
  }
  ```

  <br />

- **Read in Layout or Draw (Pass lambda provider)**:


  ```kotlin
  @Composable
  fun FadingBox(alphaProvider: () -> Float) {
      Box(Modifier.graphicsLayer { alpha = alphaProvider() })
  }
  ```

  <br />

## 2. Preventing backwards writes across frame phases

A **backwards write** occurs whenever a Compose `State` is modified in a later
frame phase (or in a downstream composable scope within the same composition
pass) than where it was read.

Compose executes frames in three strictly forward-flowing phases:
**Composition \> Layout \> Drawing**. Modifying state in a later phase (or after
reading it) forces Compose to schedule an earlier phase to re-run, creating an
unoptimized recomposition loop.

### Consequences

- **Extra Frames \& Dropped Frames**: Forces redundant composition passes across consecutive frames, wasting CPU/GPU resources and dropping frames.
- **First-Frame Correctness Issues**: Initial frames render with invalid, default, or unsettled data (such as zero size or an incorrect offset), causing visible layout popping or flashing when the second frame renders.
- **Infinite Recomposition Loops**: Occurs when layout sizing or draw logic continuously writes back to state that drives composition.

### Core rules

1. **Verify Read-Before-Write Order** : A backwards write **strictly requires a
   state read to precede the state write** (`Read -> Write` in the same composition pass, or `Composition Read -> Layout or Draw Write` across frame phases). State writes belong in event callbacks (`onClick`), coroutines (`LaunchedEffect`), or side effects (`SideEffect`); layout callbacks (`onSizeChanged`, `onPlaced`) do not count as events.
2. **Do Not Confuse `Write -> Read` with Backwards Writes** : Assigning to a `MutableState` at the top of a composable **before** any read of that state occurs (or writing in Composition and reading later in the `Canvas` Draw phase) is **not** a backwards write, because no read observer has recorded the current composition scope prior to the write (and `Composition Write ->
   Draw Read` flows forward). Instead, classify `var x by remember {
   mutableStateOf(...) }; x = getValue()` as **Redundant State Wrapping** (unnecessary `MutableState` and slot table allocation that is also fragile if someone later adds a read preceding the write) and replace it with a plain `val` or read directly in the target phase.
3. **Never write to state in `onSizeChanged`, `onPlaced`, or `LayoutModifier`
   if read in Composition** : Read sizes directly in Draw or Layout phase (`Modifier.drawWithCache`, `Modifier.layout`), hoist window-level sizing (`WindowWidthSizeClass`), or use `FlowRow`/`LazyVerticalGrid`.
4. **Never mutate state after reading it in Composition** : Use `rememberUpdatedState` when you need to capture a changing value in composition without triggering recomposition.
5. **Defer state reads to the latest possible phase**: Reading state in Layout or Draw skips Composition entirely when the state changes.

### Common patterns: backwards writes versus event-driven updates

#### A. Direct mutation in Composition (`Read -> Write` versus `Write -> Read`)

- **Bad (`Read -> Write`: Mutating state after reading it triggers immediate
  recomposition loop)**:


  ```kotlin
  @Composable
  fun BadCounter() {
      var count by remember { mutableIntStateOf(0) }
      Text("Count: $count") // 1. State read in Composition
      Button(onClick = {}) {
          // 2. State write in Composition AFTER read (Backwards write!)
          count++
      }
  }
  ```

  <br />

- **Not a Backwards Write, but Redundant/Fragile (`Write -> Read` in
  Composition)**:


  ```kotlin
  // NOT a backwards write because the write happens BEFORE any read in
  // Composition (and before Canvas reads it in the Draw phase).
  // However, wrapping a synchronous value in mutableStateOf is redundant
  // and error-prone if a read is later added before the write.
  @Composable
  fun RedundantStateWrapper(viewModel: MyViewModel) {
      var path by remember { mutableStateOf(Path()) }
      // 1. Write occurs first (no prior read in scope)
      path = viewModel.getPath()
      Canvas(Modifier.fillMaxSize()) {
          // 2. Read occurs later in Draw phase (Forward flow)
          drawPath(path, Color.Red)
      }
  }

  // Optimized: Remove redundant mutableStateOf wrapper and read in Draw
  @Composable
  fun OptimizedWrapper(viewModel: MyViewModel) {
      Canvas(Modifier.fillMaxSize()) {
          val path = viewModel.getPath()
          drawPath(path, Color.Red)
      }
  }
  ```

  <br />

#### B. Reading in Composition, writing in Layout (phase backwards write)

- **Bad (Layout writes to state read in Composition, causing first-frame
  popping)**:


  ```kotlin
  @Composable
  fun BadLayout() {
      var componentHeight by remember { mutableStateOf(0.dp) }

      // Composition reads state before Layout measures it
      if (componentHeight > 100.dp) {
          Banner()
      }

      Box(
          modifier = Modifier.onSizeChanged { size ->
              // Backwards write: Layout -> Composition
              componentHeight = size.height.dp
          }
      )
  }
  ```

  <br />

- **Optimized (Defer sizing to Layout phase or hoist to Window level)**:


  ```kotlin
  @Composable
  fun OptimizedLayout(modifier: Modifier = Modifier) {
      // Measurement and placement handled in Layout without recomposition
      Box(
          modifier = modifier.layout { measurable, constraints ->
              val placeable = measurable.measure(constraints)
              layout(placeable.width, placeable.height) {
                  placeable.placeRelative(0, 0)
              }
          }
      )
  }
  ```

  <br />

For more information, see the [Backwards Write documentation](https://developer.android.com/develop/ui/compose/performance/backwards-write).

## 3. State and snapshot optimizations

### A. Optimize whole-collection updates with `neverEqualPolicy()`

`mutableStateListOf` tracks every individual element mutation through the
snapshot system. When fine-grained element tracking is not needed, back your
collection with a standard `MutableList` wrapped in `mutableStateOf(list,
neverEqualPolicy())` and trigger recomposition using a load-bearing assignment
(`state.value = list`).


```kotlin
class ActiveQueueManager {
    private val list = mutableListOf<Item>()
    val items = mutableStateOf(list, neverEqualPolicy())

    fun enqueue(item: Item) {
        list.add(item)
        items.value = list // Load-bearing assignment triggers recomposition
    }
}
```

<br />

### B. Use `SnapshotStateList.toList()` for repeated queries and iterations

Iterating or querying a `SnapshotStateList` (or `SnapshotStateMap`) repeatedly
inside a composable registers snapshot read observations on each accessed index
or element.

When performing repeated queries (such as checking `.contains()` in a loop),
filtering, sorting, or multi-step processing on a snapshot collection, take an
atomic snapshot using `.toList()` (or `.toSet()` / `.toMap()`) once at the start
of the scope and perform operations on that read-only snapshot:

- **Bad (Repeated contains queries inside loop register fine-grained
  dependencies on every element)**:


  ```kotlin
  @Composable
  fun ItemSelector(
      items: List<Item>,
      selectedIds: SnapshotStateList<String>,
  ) {
      Column {
          items.forEach { item ->
              // Avoid: Repeatedly querying the snapshot list inside the loop
              val isSelected = selectedIds.contains(item.id)
              ItemRow(item = item, isSelected = isSelected)
          }
      }
  }
  ```

  <br />

- **Optimized (Take a read-only snapshot Set once for `O(1)` queries)**:


  ```kotlin
  @Composable
  fun ItemSelector(
      items: List<Item>,
      selectedIds: SnapshotStateList<String>,
  ) {
      // Convert to a local Set once per recomposition for fast O(1) lookups
      val selectedSet = selectedIds.toSet()
      Column {
          items.forEach { item ->
              val isSelected = selectedSet.contains(item.id)
              ItemRow(item = item, isSelected = isSelected)
          }
      }
  }
  ```

  <br />

### C. Extract repeated state reads into local variables

Reading Compose `State` properties (such as `transition.currentState`,
`scrollState.value`, or `state.value`) multiple times in a function registers
redundant snapshot lookups. Cache the state read into a local variable at the
top of the block.

- **Bad (Repeated snapshot state reads)**:


  ```kotlin
  val label = when (transition.currentState) {
      State.Idle -> "Idle"
      State.Running -> "Running: ${transition.currentState}"
      State.Finished -> "Finished: ${transition.currentState}"
  }
  ```

  <br />

- **Optimized (Single read cached in local variable)**:


  ```kotlin
  val currentState = transition.currentState
  val label = when (currentState) {
      State.Idle -> "Idle"
      State.Running -> "Running: $currentState"
      State.Finished -> "Finished: $currentState"
  }
  ```

  <br />

### D. Use `computedStateOf` versus `derivedStateOf` (Compose 1.13+)

When deriving state from frequently changing inputs (such as checking
`scrollState.value > 0` on every scroll tick), reading the raw state directly in
composition invalidates the composable on every frame.

In Compose 1.13+, **`computedStateOf`** is introduced alongside
**`derivedStateOf`**:

- **`computedStateOf`** : Computes its calculation on **every read** without caching the value.
  - **First read** : Slightly faster than `derivedStateOf` because it avoids cache initialization and snapshot dependency bookkeeping.
  - **Second read** : Slightly slower than `derivedStateOf` because it re-executes the calculation.
  - **Read-after-write (invalidation)** : Significantly faster than `derivedStateOf` because it does not have the expensive cache invalidation path, cache write-back, and snapshot apply-listener dispatch.
- **`derivedStateOf`** : Caches the result of its calculation and returns the cached value as long as dependency states have not changed.
  - Has a slower invalidation path when its dependency states change frequently, due to cache clearing, re-computation, cache write-back, and advancing snapshots or triggering apply listeners.

#### When to use which

1. **Prefer `computedStateOf` (Compose 1.13+) or `remember { derivedStateOf {
   ... } }` (Compose \< 1.13)** for simple condition checks, boolean flags, or
   lightweight derivations driven by fast-changing states (such as scroll
   offsets or slider positions). Check `gradle/libs.versions.toml` or
   `build.gradle(.kts)` for the project's Compose version before using
   `computedStateOf`:


   ```kotlin
   @Composable
   fun ScrollToTopButton(scrollState: ScrollState) {
       // Compose 1.13+: Invalidates composition only when > 0 changes,
       // with minimal invalidation overhead compared to derivedStateOf.
       // On Compose < 1.13, use remember { derivedStateOf { ... } }.
       val showButton by remember { computedStateOf { scrollState.value > 0 } }

       if (showButton) {
           FloatingActionButton(onClick = { /* ... */ }) {
               Icon(Icons.Default.ArrowUpward, "Scroll to Top")
           }
       }
   }
   ```

   <br />

2. **Use `derivedStateOf`** when the calculation itself is computationally
   heavy and is read multiple times across the composition pass, so that the
   benefits of multi-read caching outweigh the invalidation cost.

   - *Read frequency nuance for trivial calculations* : `derivedStateOf` can also outperform `computedStateOf` for trivial calculations if read frequently enough per composition pass. Specifically, `derivedStateOf` starts to make up for the invalidation cost for trivial calculations when read about `10 / numberOfStatesReadPerCalculation` times per composition (assuming the calculation never invalidates between reads).
3. **Prefer plain Kotlin getters (`get()`)** for trivial property lookups or
   inverted booleans where invalidation dampening is not needed:


   ```kotlin
   var isLocked by mutableStateOf(false)
   val isReady: Boolean get() = !isLocked
   ```

   <br />

4. **Avoid `produceState` + `snapshotFlow`** for synchronous derivations:
   Wrapping `snapshotFlow` in `produceState` adds unnecessary coroutine
   allocation, context switching, and flow dispatch overhead compared to
   `computedStateOf` or `derivedStateOf`.

   - **Bad (Unnecessary coroutine and flow dispatch)**:


     ```kotlin
     val isScrolledToTop by produceState(initialValue = true, scrollState) {
         snapshotFlow { scrollState.firstVisibleItemIndex == 0 }
             .collect { value = it }
     }
     ```

     <br />

   - **Optimized (Synchronous snapshot derivation)**:


     ```kotlin
     // Compose 1.13+:
     val isScrolledToTop by remember {
         computedStateOf { scrollState.firstVisibleItemIndex == 0 }
     }
     // Compose < 1.13:
     val isScrolledToTop by remember {
         derivedStateOf { scrollState.firstVisibleItemIndex == 0 }
     }
     ```

     <br />

## 4. Strong skipping mode and stability

Don't annotate everything with `@Stable` or `@Immutable` (or wrap collections
in `ImmutableList`) in an attempt to force every composable to skip.

**Skipping a composable is not free**. Forcing stability can actually
degrade runtime performance instead of improving it.

### A. How Compose handles skipping under the hood

When a composable is made skippable, the Compose compiler generates slot table
storage and comparison logic for every parameter:

    // What you write
    @Composable
    fun ContactCell(contact: Contact) {
        /* Your function body */
    }

    // What the Compose compiler generates (simplified)
    @Composable
    fun ContactCell(
        contact: Contact,
        $composer: Composer?,
        $changed: Int
    ) {
        val $dirty = $changed or $composer.changed(contact)

        if ($dirty) {
            /* Your function body */
        } else {
            $composer.skipToGroupEnd()
        }
    }

Every parameter passed to a composable is effectively stored in the slot table
as if it were `remember`ed. On every recomposition pass:

1. Compose reads the previous parameter value from the slot table.
2. It executes an equality check between the old and new parameter values.
3. If any check fails, the composable executes its body; if all checks pass, it calls `$composer.skipToGroupEnd()`.

This slot table caching and comparison logic incurs memory and CPU overhead on
every recomposition attempt.

### B. Instance equality (`===`) versus structural equality (`.equals()`)

Under **Strong Skipping Mode** (enabled by default in modern Compose):

- **Unstable parameters** are compared using **instance equality** (`===` / `$composer.changedInstance(param)`).
- **Stable parameters** (primitives, `@Stable` / `@Immutable` annotated classes, or types with all stable properties) are compared using **structural equality** (`.equals()` / `$composer.changed(param)`).

#### Why `@Stable`, `@Immutable`, and `ImmutableList` can hurt performance

Unstable parameters are not prevented from skipping: if the same instance is
passed across recompositions (`param === oldParam`), Compose skips the
composable quickly with a fast `O(1)` pointer check.

When you annotate a class with `@Stable` or `@Immutable` (or replace
`kotlin.collections.List` with `ImmutableList`), Compose switches from fast
`===` pointer comparison to deep `.equals()` structural comparison.

- **Expensive Deep Comparisons** : In complex models or large collections (e.g. hundreds of items), running `equals()` on every recomposition can be far more expensive than executing the composable's body. Method traces often reveal that `Model.equals` consumes the vast majority of frame time.
- **Composition Counts != Benchmarks** : A composable that skips but spends 3ms evaluating a deep `.equals()` check is significantly slower than a composable that recomposes in 0.2ms.
- **Never Recommend Replacing `List<T>` with `ImmutableList<T>`** : Never recommend converting `kotlin.collections.List<T>` properties into `kotlinx.collections.immutable.ImmutableList<T>` (or Guava `ImmutableList` via `stability_config.txt`) on `@Stable` or `@Immutable` models. Standard `List<T>` already skips via `O(1)` reference equality (`===`) under Strong Skipping Mode, whereas `ImmutableList<T>` forces `O(N)` element-by-element `.equals()` traversal.

#### Scope guard: when to remove existing `@Stable` or `@Immutable`

When auditing or refactoring an existing codebase, apply a strict scope guard
before recommending changes to existing stability annotations:

1. **Only Recommend Removing `@Stable` / `@Immutable` for Large Datasets in
   Lazy Layouts** : Strongly recommend removing existing `@Stable` or `@Immutable` annotations from data models **only** when **both** conditions apply:
   - The model is used within a **lazy layout** (`LazyColumn`, `LazyRow`, `LazyVerticalGrid`, `LazyHorizontalGrid`, `Pager`, `ScalingLazyColumn`) as the input collection container or item model, **AND**
   - It holds a **large dataset or deep nested collection tree** (such as a full screen stream, hundreds of items/tags, or nested lists like `Post` containing `List<Paragraph>` and `List<Markup>`), where `O(N)` structural equality checks during scrolling or recomposition are expensive.
2. **Leave Existing Annotations Untouched Elsewhere** : For all other use cases where a codebase already has `@Stable`, `@Immutable`, or `ImmutableList`--- such as non-lazy screen/component models, standalone UI elements, or models with small/bounded collections (e.g. a card model holding 2--5 badges, thumbnails, or metadata rows) where dataset size is small or hard to estimate statically---**leave the existing annotations untouched**.

### C. Parameter unwrapping and equality propagation pitfall (`ImmutableList`)

The Compose compiler includes an optimization where propagating the exact same
object reference down the composable hierarchy allows Compose to reuse previous
equality results and skip redundant comparisons.

When a parent `@Composable` passes the exact same parameter reference to a child
`@Composable`, the Compose compiler propagates its dirty bitmask so the child
can skip re-evaluating `.equals()`.

However, when a parent `@Composable` receives a `data class` containing a large
`ImmutableList` and passes the unwrapped `ImmutableList` to a child
`@Composable`, **Compose runs `O(N)` structural equality twice**:


```kotlin
// Bad: Both HomeScreen (via data class .equals()) and HomeContent evaluate
// O(N) structural equality on the same ImmutableList in a single frame
data class HomeScreenModel(
    val header: String,
    val items: ImmutableList<ItemModel>
)

@Composable
private fun HomeScreen(model: HomeScreenModel) {
    TopAppBar(title = { Text(model.header) })
    // Unwrapping model.items to pass to a child @Composable
    HomeContent(model.items)
}

// Because contentList is an ImmutableList, HomeContent must re-evaluate O(N)
// .equals() on the list itself, even though HomeScreen already compared model
@Composable
private fun HomeContent(contentList: ImmutableList<ItemModel>) {
    LazyColumn {
        items(contentList) { /* ... */ }
    }
}
```

<br />

In the preceding example, because `items` is an `ImmutableList` (which the
compiler treats as stable and compares with `.equals()`) inside a `data class`:

1. `HomeScreen` evaluates `model.equals(oldModel)`, which traverses `model.items.equals(oldModel.items)` in `O(N)` time.
2. `HomeContent` receives `model.items` rather than `model`, so the compiler cannot propagate `model`'s `$dirty` flag and calls `contentList.equals(oldContentList)` a second time (`O(N)`).

#### Scope guard: when parameter unwrapping is *not* an issue

Do **not** flag parameter unwrapping unless **all** of the following conditions
are met:

1. **Both caller and callee are skippable `@Composable` functions** : Plain Kotlin functions, node-building helpers, and `LazyListScope` / `LazyGridScope` DSL extensions (for example, `fun verticalGridUi(itemList =
   content.itemList, ...)`) are not skippable composables and do not generate `$composer.changed(...)` equality checks in the slot table.
2. **The parent object is a `@Composable` parameter with structural
   `.equals()`** : If the container object is read from state inside the function body (such as `val content = model.contentState.value`) rather than passed as a `@Composable` parameter, or is a regular `class` without a deep `data class` `.equals()` implementation, no parent equality check occurred in the first place.
3. **The unwrapped property itself triggers expensive `O(N)` `.equals()`** : The unwrapped property must be an `ImmutableList` (or a collection marked stable in `stability_config.txt`). **Never** flag unwrapping a standard `kotlin.collections.List<T>` (`Set<T>`, `Map<K, V>`) or primitive/value-type properties (`Int`, `Dp`, `String`, `Boolean`, enums)---under Strong Skipping Mode, standard `List<T>` is compared via `O(1)` reference equality (`===`), so unwrapping `content.itemList: List<T>` has zero deep-comparison overhead.

### D. Don't over-index on recomposition counts

Recomposition counts are not a substitute for benchmarks. Once composition is
scheduled for a screen, skipping a single intermediate composable saves minimal
time while still incurring the overhead of remembering parameters and evaluating
equality checks. Rather than trying to eliminate every individual recomposition,
focus on **avoiding unnecessary compositions** altogether by deferring rapidly
changing state reads to the Layout or Draw phases (for example, using lambda
modifiers and `graphicsLayer`), and
**keep composables lightweight** so they execute quickly when recomposition
occurs.

### E. Guidelines for designing fast composables

1. **Prefer fewer, easy-to-compare arguments** : Pass primitives (`String`, `Int`, `Long`, `Dp`) or standard `List<T>` references (which compare via fast `===`) rather than large composite objects whenever practical.
2. **Avoid double `O(N)` comparisons on `ImmutableList`** : Do not wrap large collections in `ImmutableList` inside `data class` models where both parent and child `@Composable` functions will run `O(N)` `.equals()` on the list; use standard `List<T>` so Compose uses `O(1)` `===` reference equality.
3. **Merge related effects and state**: Minimize the number of remembered objects and dispatched effects to reduce slot table overhead.
4. **Be selective with `@Stable` / `@Immutable`** : Only add `@Stable` or `@Immutable` to small, flat UI models from non-Compose modules where instances are frequently recreated and `.equals()` is cheap `O(1)`. Never add `@Stable`, `@Immutable`, or `ImmutableList` to large collections or deep data graphs. When auditing existing code, only remove `@Stable` or `@Immutable` from models that feed lazy layouts (`LazyColumn`, `LazyRow`, `LazyGrid`) with large datasets or deep nested collections; leave existing annotations on non-lazy or small-collection models untouched.
5. **Always benchmark**: Use Macrobenchmark or Microbenchmark to measure real frame timing and CPU impact. Never rely solely on recomposition counts to judge performance.

- **Optimized (Remove `@Immutable` on large lazy-layout models to rely on fast
  `===` instance equality, and pass lightweight primitives)**:


  ```kotlin
  // Unstable model skips using === pointer check when instance is unchanged
  data class FeedState(
      val feedId: String,
      val items: List<FeedItem>
  )

  @Composable
  fun FeedScreen(state: FeedState) {
      // Pass only the primitive ID needed by the header
      FeedHeader(feedId = state.feedId)
      // Pass the state or items directly
      FeedList(items = state.items)
  }
  ```

  <br />

For more information, see the [Strong Skipping Mode documentation](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping).

## 5. Using `remember` and calculation keys

While `remember` is essential for preserving state and caching expensive
operations across recompositions, each call stores its result and keys in the
slot table and checks key equality on every recomposition. Use `remember` when
object identity must be preserved or when caching nontrivial work (such as
formatters, shaders, paths, or list transformations), rather than wrapping
trivial expressions or cheap allocations whose key comparisons cost more than
the allocation itself.

### Require explicit calculation keys on `remember`

`remember { ... }` with no keys executes only on initial composition and caches
the result for the lifetime of the Composable. If the Composable recomposes with
new parameter values, omitting keys returns the stale cached value. For standard
Kotlin calculations and object instantiations, always pass every external
variable or parameter referenced inside the `remember` block as a key.

- **Exception for `derivedStateOf`** : When wrapping `derivedStateOf`, do
  **not** pass the snapshot state properties read inside the calculation
  lambda as `remember` keys:


  ```kotlin
  // Bad: passing snapshot state property as a key breaks derivedStateOf
  val showButton by remember(scrollState.firstVisibleItemIndex) {
      derivedStateOf { scrollState.firstVisibleItemIndex > 0 }
  }

  // Optimized: derivedStateOf tracks firstVisibleItemIndex internally
  val showButton by remember {
      derivedStateOf { scrollState.firstVisibleItemIndex > 0 }
  }
  ```

  <br />

  `derivedStateOf` dynamically tracks snapshot state reads internally and
  invalidates only when the derived result actually changes. Passing state
  values as `remember` keys forces the `remember` block to recreate on every
  single state mutation, completely destroying `derivedStateOf`'s ability to
  skip recompositions. Only pass the `State` holder instance as a key if the
  instance itself can change.
- **Bad (Omitting keys for standard calculation returns stale value upon
  parameter change)**:


  ```kotlin
  @Composable
  fun FormattedDateLabel(timestamp: Long, locale: Locale) {
      val formattedDate = remember {
          SimpleDateFormat("yyyy-MM-dd", locale).format(Date(timestamp))
      }
      Text(text = formattedDate)
  }
  ```

  <br />

- **Optimized (Pass referenced calculation parameters as keys)**:


  ```kotlin
  @Composable
  fun FormattedDateLabel(timestamp: Long, locale: Locale) {
      val formattedDate = remember(timestamp, locale) {
          SimpleDateFormat("yyyy-MM-dd", locale).format(Date(timestamp))
      }
      Text(text = formattedDate)
  }
  ```

  <br />