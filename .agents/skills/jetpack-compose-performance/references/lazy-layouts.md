## 1. Lazy layout directives

Implement strict item keying, content-type mapping, and index-lookup
optimizations to prevent unnecessary item recompositions, layout passes, and
dropped frames during scrolling.
> **Scope Guard (Virtualized Containers Only)** : The directives in this section
> apply **strictly** to lazy, virtualized layouts (`LazyColumn`, `LazyRow`,
> `LazyVerticalGrid`, `LazyHorizontalGrid`, `HorizontalPager`, `VerticalPager`).
> Standard non-virtualized containers (`Column`, `Row`, `Box`) iterating over
> collections using standard Kotlin `for` or `forEach` loops are **not** lazy
> layouts. Do **not** flag standard `Column`/`Row` loops for missing `key` or
> `contentType` parameters.

### 1. Require keys and content types

Always provide a stable `key` and a `contentType` for all items.

- **`key`**: Enables Compose to maintain item state across reorderings and list updates without recreating item compositions from scratch.
- **`contentType`**: Enables Compose to reuse item compositions and node slots when recycling items of the same visual type, significantly reducing layout inflation overhead during fast scrolling.


```kotlin
LazyColumn {
    items(
        items = itemList,
        key = { item -> item.id },
        contentType = { item -> item.type }
    ) { item ->
        ItemRow(item = item)
    }
}
```

<br />

- **Ensure Keys are Bundle-Saveable** : Keys must be saveable in an Android `Bundle` because `LazyColumn` uses `rememberSaveable` internally to persist scroll states and item state across configuration changes and process death.
  - **Do NOT** use custom data classes as keys unless they implement `Parcelable` or `Serializable`.
  - **Do** use unique primitive types (like `Int` or `Long`). If needed, combine fields into a unique string: `key = { "${it.id}_${it.timestamp}"
    }`. Prefer `Int` and `Long` over `String` for minimal memory and hashing overhead.
- **Avoid Duplicate and Unstable Keys** : Ensure keys are globally unique and deterministic. Never use `hashCode()` or list indexes as keys, as they change when items are reordered, inserted, or filtered.

### 2. Avoid in-item collection searches and observable state queries

Do not perform collection searches, linear index scans, or observable state
queries inside item layout blocks or outer list item scopes:

- **Linear Index Scans (`indexOf`, `find`)** : Searching the list for an item's
  index inside the layout block (for example, `list.indexOf(item)`) turns a
  linear layout pass into an `O(N²)` operation and causes
  `IndexOutOfBoundsException` during asynchronous mutations. Use
  `itemsIndexed` instead.

  - **Bad (`O(N²)` linear scan per item)**:


    ```kotlin
    LazyColumn {
        items(items = itemList, key = { it.id }) { item ->
            // O(N) lookup inside each item!
            val index = itemList.indexOf(item)
            ItemRow(index = index, item = item)
        }
    }
    ```

    <br />

  - **Optimized (Direct index passing with `itemsIndexed`)**:


    ```kotlin
    LazyColumn {
        itemsIndexed(
            items = itemList,
            key = { _, item -> item.id },
        ) { index, item ->
            ItemRow(index = index, item = item)
        }
    }
    ```

    <br />

- **Observable Selection \& State Queries** : Never use `mutableStateListOf`
  (`SnapshotStateList`) for tracking selected IDs where `.contains()` and
  `.remove()` perform `O(N)` linear scans; use an immutable `Set` in
  `MutableState` (`mutableStateOf(emptySet<String>())`) or
  `mutableStateSetOf` (`SnapshotStateSet` in Compose 1.8+) for `O(1)`
  lookups. Also, reading an observable collection state (like
  `selectedIds.contains(item.id)`) inside the outer `items(...) { ... }`
  block subscribes the entire list or section to changes in that collection.
  Toggling a single item invalidates the parent scope and forces a full
  recomposition of all visible items. Defer the state read to the leaf item
  composable by passing a lambda provider (`isSelected: () -> Boolean`).

  - **Bad (`mutableStateListOf` `O(N)` lookups and reading state in outer
    `items` scope triggers full list or grid recomposition on toggle)**:


    ```kotlin
    @Composable
    fun TopicGrid(sections: List<TopicSection>) {
        val selectedTopicIds = remember { mutableStateListOf<String>() }
        LazyColumn {
            items(sections, key = { it.id }) { section ->
                Column {
                    Text(text = section.title)
                    section.topics.forEach { topic ->
                        // Avoid: O(N) List.contains() read in items() scope
                        // invalidates the whole section!
                        val isSelected = selectedTopicIds.contains(topic.id)
                        TopicChip(
                            topic = topic,
                            isSelected = isSelected,
                            onToggle = {
                                if (isSelected) {
                                    selectedTopicIds.remove(topic.id)
                                } else {
                                    selectedTopicIds.add(topic.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    ```

    <br />

  - **Optimized (Use `Set<String>` (`mutableStateOf(emptySet())` or
    `mutableStateSetOf()`) for `O(1)` lookup and pass lambda to leaf
    composable to isolate recomposition to the clicked item)**:


    ```kotlin
    @Composable
    fun TopicGrid(sections: List<TopicSection>) {
        var selectedTopicIds by remember {
            mutableStateOf(emptySet<String>())
        }
        LazyColumn {
            items(
                items = sections,
                key = { it.id },
                contentType = { "section" },
            ) { section ->
                Column {
                    Text(text = section.title)
                    section.topics.forEach { topic ->
                        TopicChip(
                            topic = topic,
                            // O(1) Set read deferred to TopicChip's scope
                            isSelectedProvider = {
                                selectedTopicIds.contains(topic.id)
                            },
                            onToggle = {
                                val selected =
                                    selectedTopicIds.contains(topic.id)
                                selectedTopicIds = if (selected) {
                                    selectedTopicIds - topic.id
                                } else {
                                    selectedTopicIds + topic.id
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun TopicChip(
        topic: Topic,
        isSelectedProvider: () -> Boolean,
        onToggle: () -> Unit
    ) {
        // Read occurs inside leaf scope
        val isSelected = isSelectedProvider()
        val status = if (isSelected) "Selected" else "Unselected"
        Box(modifier = Modifier.clickable { onToggle() }) {
            Text(text = "${topic.title} ($status)")
        }
    }
    ```

    <br />

### 3. Avoid `derivedStateOf` for direct collection sizes and counts

Do not wrap direct collection property accesses (such as `list.size` or
`list.isEmpty()`) in `derivedStateOf`. Accessing a property on an already
available list or snapshot collection is an `O(1)` read. Wrapping it in
`derivedStateOf` allocates a `DerivedState` wrapper object, snapshot record
tracking structures, and observer subscriptions that cost far more than reading
the integer property directly.

### 4. Avoid lazy layouts for small or bounded collections

**Tip:** For small static or bounded dynamic collections (e.g. 1 to 10 items
such as tags, buttons, form rows, or chips), prefer using a standard `Row` or
`Column` with a `for` or `forEach` loop instead of `LazyRow` or `LazyColumn`.
For small collections, composing all items eagerly reduces layout, prefetching,
and measurement overhead compared to lazy list virtualization machinery.
Benchmark this change before applying broad changes to code.

- **Bad (Lazy virtualization overhead for tiny static list)**:


  ```kotlin
  LazyRow {
      items(items = listOf("Work", "Personal", "Family")) { tag ->
          FilterChip(tag = tag)
      }
  }
  ```

  <br />

- **Optimized (Standard Row with forEach)**:


  ```kotlin
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Work", "Personal", "Family").forEach { tag ->
          FilterChip(tag = tag)
      }
  }
  ```

  <br />

### 5. Hoist shared `Flow` and state collections out of lazy items

**Critical:** Never collect a shared `Flow`, `StateFlow`, or observable timer
directly inside list item composables using `collectAsState()` or
`collectAsStateWithLifecycle()`.

When a shared flow is collected inside item composables, every visible item in
the `LazyColumn` or `LazyRow` creates an independent subscription to that same
flow. When the flow emits an update (such as an idle timer tick or periodic
sync), it invalidates and recomposes all visible items simultaneously, creating
massive CPU spikes and dropped frames during scrolling.

Instead, **hoist the flow collection once** to the parent container above the
`LazyColumn`, derive the required state, and pass stable or primitive values
down to the individual item composables.

- **Bad (Each visible item creates an independent subscription to the shared
  timer)**:


  ```kotlin
  @Composable
  fun FeedItem(item: Post) {
      // Avoid: Multiplied subscriptions across all visible items!
      val secondsSinceLastScroll by MainFeedIdleTracker
          .secondsSinceLastScrollFlow
          .collectAsState(0L)
      val isDwellTriggered = secondsSinceLastScroll >= 5L
      PostActions(isDwellTriggered = isDwellTriggered)
  }

  @Composable
  fun FeedList(posts: List<Post>) {
      LazyColumn {
          items(posts, key = { it.id }) { post ->
              FeedItem(post)
          }
      }
  }
  ```

  <br />

- **Optimized (Hoist subscription once above LazyColumn, pass stable value
  down)**:


  ```kotlin
  @Composable
  fun FeedList(posts: List<Post>) {
      // Hoist once: Parent owns the single subscription
      val secondsSinceLastScroll by MainFeedIdleTracker
          .secondsSinceLastScrollFlow
          .collectAsStateWithLifecycle(0L)
      val isDwellTriggered = secondsSinceLastScroll >= 5L

      LazyColumn {
          items(posts, key = { it.id }) { post ->
              FeedItem(post = post, isDwellTriggered = isDwellTriggered)
          }
      }
  }

  @Composable
  fun FeedItem(post: Post, isDwellTriggered: Boolean) {
      PostActions(isDwellTriggered = isDwellTriggered)
  }
  ```

  <br />

### 6. Propagate `CompositionLocal`s down instead of reading in lazy items

Avoid querying `CompositionLocal`s (such as `LocalContext.current` or
`LocalDensity.current`) directly inside individual lazy item composables.

Querying a `CompositionLocal` inside every item adds repetitive map lookup
overhead during item composition and subscribes each individual item to the
CompositionLocal's updates. Instead, read the CompositionLocal once in the
parent screen or list container and pass the resolved values down as
parameters.

- **Bad (Querying scopes and context per item)**:


  ```kotlin
  @Composable
  fun SnackItem(snack: Snack) {
      val sharedScope = LocalSharedTransitionScope.current
          ?: error("No scope")
      val context = LocalContext.current
      // ...
  }
  ```

  <br />

- **Optimized (Pass resolved dependencies down as parameters)**:


  ```kotlin
  @Composable
  fun SnackList(
      snacks: List<Snack>,
      sharedScope: SharedTransitionScope,
  ) {
      val context = LocalContext.current
      LazyColumn {
          items(snacks, key = { it.id }, contentType = { "snack" }) { snack ->
              SnackItem(
                  snack = snack,
                  sharedScope = sharedScope,
                  context = context,
              )
          }
      }
  }
  ```

  <br />

### 7. Defer shared transitions behind visibility indicators

Attaching `Modifier.sharedBounds` or `Modifier.sharedElement` to every item in
a lazy layout adds significant modifier allocation, shared state tracking, and
snapshot record instantiation overhead---even for offscreen or pre-fetched items
that are not visible.

In fast-scrolling or large lazy lists, gate shared transitions behind
visibility indicators (such as `Modifier.onVisibilityChanged` or viewport
visibility checks) so only rendered, visible items instantiate transition
scopes and coordinate tracking.