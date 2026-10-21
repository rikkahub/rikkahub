Compose provides many [modifiers](https://developer.android.com/develop/ui/compose/modifiers) for common behaviors right out of the box,
but you can also create your own custom modifiers.

Modifiers have multiple parts:

- A modifier factory
  - This is an extension function on `Modifier`, which provides an idiomatic API for your modifier and allows modifiers to be chained together. The modifier factory produces the modifier elements used by Compose to modify your UI.
- A modifier element
  - This is where you can implement the behavior of your modifier.

There are multiple ways to implement a custom modifier depending on the
functionality needed. Often, the simplest way to implement a custom modifier is
to implement a custom modifier factory that combines other already defined
modifier factories. If you need more custom behavior, implement the modifier
element using the `Modifier.Node` APIs, which are lower level but provide more
flexibility.

## Chain existing modifiers together

It is often possible to create custom modifiers by using existing modifiers. For
example, [`Modifier.clip()`](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier#(androidx.compose.ui.Modifier).clip(androidx.compose.ui.graphics.Shape)) is implemented using the `graphicsLayer`
modifier. This strategy uses existing modifier elements, and you provide your
own custom modifier factory.

Before implementing your own custom modifier, see if you can use the same
strategy.


```kotlin
fun Modifier.clip(shape: Shape) = graphicsLayer(shape = shape, clip = true)
```

<br />

Or, if you find you are repeating the same group of modifiers often, you can
wrap them into your own modifier:


```kotlin
fun Modifier.myBackground(color: Color) = padding(16.dp)
    .clip(RoundedCornerShape(8.dp))
    .background(color)
```

<br />

## Create a custom modifier using a composable modifier factory

You can also create a custom modifier using a composable function to pass values
to an existing modifier. This is known as a composable modifier factory.

> [!NOTE]
> **Note:** `composed {}` is no longer recommended due to performance concerns. See [Migrate from `Modifier.composed` to `Modifier.Node`](https://developer.android.com/develop/ui/compose/custom-modifiers-node) for details on how to migrate away from this modifier.

Using a composable modifier factory to create a modifier also lets you use
higher level compose APIs, such as [`animate*AsState`](https://developer.android.com/develop/ui/compose/animation/value-based#animate-as-state) and other [Compose
state backed animation APIs](https://developer.android.com/develop/ui/compose/animation/choose-api). For example, the following snippet shows a
modifier that animates an alpha change when enabled/disabled:


```kotlin
@Composable
fun Modifier.fade(enable: Boolean): Modifier {
    val alpha by animateFloatAsState(if (enable) 0.5f else 1.0f)
    return this then Modifier.graphicsLayer { this.alpha = alpha }
}
```

<br />

> [!WARNING]
> **Warning:** When creating custom modifiers, don't break the modifier chain. You must always reference `this` or else any modifiers previously added will be dropped. You can use `this then Modifier` as in the preceding example or implicitly using `return graphicsLayer { this.alpha = alpha }`.

If your custom modifier is a convenience method to provide default values from a
`CompositionLocal`, the easiest way to implement this is to use a composable
modifier factory:


```kotlin
@Composable
fun Modifier.fadedBackground(): Modifier {
    val color = LocalContentColor.current
    return this then Modifier.background(color.copy(alpha = 0.5f))
}
```

<br />

This approach has some caveats, which are detailed in the following sections.

#### `CompositionLocal` values are resolved at the call site of the modifier factory

When creating a custom modifier using a composable modifier factory, composition
locals take the value from the composition tree where they are created, not
used. This can lead to unexpected results. For example, consider the
composition local modifier example mentioned previously, implemented slightly
differently using a composable function:


```kotlin
@Composable
fun Modifier.myBackground(): Modifier {
    val color = LocalContentColor.current
    return this then Modifier.background(color.copy(alpha = 0.5f))
}

@Composable
fun MyScreen() {
    CompositionLocalProvider(LocalContentColor provides Color.Green) {
        // Background modifier created with green background
        val backgroundModifier = Modifier.myBackground()

        // LocalContentColor updated to red
        CompositionLocalProvider(LocalContentColor provides Color.Red) {

            // Box will have green background, not red as expected.
            Box(modifier = backgroundModifier)
        }
    }
}
```

<br />

If this is not how you expect your modifier to work, use a custom
[`Modifier.Node`](https://developer.android.com/develop/ui/compose/custom-modifiers#implement-custom) instead, as composition locals will be
correctly resolved at the usage site and can be safely hoisted.

#### Composable function modifiers are never skipped

Composable factory modifiers are never [skipped](https://developer.android.com/develop/ui/compose/mental-model#skips) because composable functions
that have return values cannot be skipped. This means your modifier function
will be called on every recomposition, which may be expensive if it recomposes
frequently.

#### Composable function modifiers must be called within a composable function

Like all composable functions, a composable factory modifier must be called from
within composition. This limits where a modifier can be hoisted to, as it can
never be hoisted out of composition. In comparison, non-composable modifier
factories can be hoisted out of composable functions to allow easier reuse and
improve performance:


```kotlin
val extractedModifier = Modifier.background(Color.Red) // Hoisted to save allocations

@Composable
fun Modifier.composableModifier(): Modifier {
    val color = LocalContentColor.current.copy(alpha = 0.5f)
    return this then Modifier.background(color)
}

@Composable
fun MyComposable() {
    val composedModifier = Modifier.composableModifier() // Cannot be extracted any higher
}
```

<br />

## Implement custom modifier behavior using `Modifier.Node`

[`Modifier.Node`](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier.Node) is a lower level API for creating modifiers in Compose. It
is the same API that Compose implements its own modifiers in and is the most
performant way to create custom modifiers.

> [!NOTE]
> **Note:** There is another API for creating custom modifiers, [`composed {}`](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier#(androidx.compose.ui.Modifier).composed(kotlin.Function1,kotlin.Function1)). This API is no longer recommended due to the performance issues it created. `Modifier.Node` was designed from the ground up to be far more performant than composed modifiers. For more details on the problems with composed modifiers, see the Android Dev Summit talk [Compose Modifiers Deep Dive](https://www.youtube.com/watch?v=BjGX2RftXsU).

### Implement a custom modifier using `Modifier.Node`

There are three parts to implementing a custom modifier using Modifier.Node:

- A [`Modifier.Node`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Modifier.kt;l=184) implementation that holds the logic and state of your modifier.
- A [`ModifierNodeElement`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/ModifierNodeElement.kt;l=39) that creates and updates modifier node instances.
- An optional modifier factory, as detailed previously.

`ModifierNodeElement` classes are stateless and new instances are allocated each
recomposition, whereas `Modifier.Node` classes can be stateful and will survive
across multiple recompositions, and can even be reused.

The following section describes each part and shows an example of building a
custom modifier to draw a circle.

#### `Modifier.Node`

The `Modifier.Node` implementation (in this example, `CircleNode`) implements
the functionality of your custom modifier.


```kotlin
// Modifier.Node
private class CircleNode(var color: Color) : DrawModifierNode, Modifier.Node() {
    override fun ContentDrawScope.draw() {
        drawCircle(color)
    }
}
```

<br />

In this example, it draws the circle with the color passed in to the modifier
function.

A node implements `Modifier.Node` as well as zero or more node types. There are
different node types based on the functionality your modifier requires. The
preceding example needs to be able to draw, so it implements `DrawModifierNode`,
which lets it override the draw method.

The available types are as follows:

|---|---|---|
| **Node** | **Usage** | **Sample Link** |
| [`LayoutModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/LayoutModifierNode.kt) | A `Modifier.Node` that changes how its wrapped content is measured and laid out. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/LayoutSample.kt;l=198) |
| [`DrawModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/DrawModifierNode.kt) | A `Modifier.Node` that draws into the space of the layout. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt;l=313) |
| [`CompositionLocalConsumerModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/CompositionLocalConsumerModifierNode.kt) | Implementing this interface lets your `Modifier.Node` read composition locals. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierCompositionLocalSample.kt;l=64) |
| [`SemanticsModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/SemanticsModifierNode.kt) | A `Modifier.Node` that adds semantics key/value for use in testing, accessibility, and similar use cases. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt;l=338) |
| [`PointerInputModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/PointerInputModifierNode.kt) | A `Modifier.Node` that receives [PointerInputChanges.](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/input/pointer/PointerEvent.kt) | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt;l=366) |
| [`ParentDataModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/ParentDataModifierNode.kt) | A `Modifier.Node` that provides data to the parent layout. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/Box.kt;l=295) |
| [`LayoutAwareModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/LayoutAwareModifierNode.kt) | A `Modifier.Node` which receives `onMeasured` and `onPlaced` callbacks. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt;l=405) |
| [`GlobalPositionAwareModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/GlobalPositionAwareModifierNode.kt) | A `Modifier.Node` which receives an `onGloballyPositioned` callback with the final `LayoutCoordinates` of the layout when the global position of the content may have changed. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt;l=405) |
| [`ObserverModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/ObserverModifierNode.kt) | `Modifier.Node`s that implement `ObserverNode` can provide their own implementation of `onObservedReadsChanged` that will be called in response to changes to snapshot objects read within an `observeReads` block. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierCompositionLocalSample.kt;l=64) |
| [`DelegatingNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/DelegatingNode.kt) | A `Modifier.Node` which is able to delegate work to other `Modifier.Node` instances. This can be useful to compose multiple node implementations into one. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/samples/src/main/java/androidx/compose/ui/samples/ModifierSamples.kt) |
| [`TraversableNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/TraversableNode.kt;l=28) | Allows `Modifier.Node` classes to traverse up/down the node tree for classes of the same type or for a particular key. | [Sample](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/integration-tests/ui-demos/src/main/java/androidx/compose/ui/demos/modifier/TraverseModifierDemo.kt;l=123) |

Nodes are automatically invalidated when update is called on their corresponding
element. Because our example is a `DrawModifierNode`, any time update is called
on the element, the node triggers a redraw and its color correctly updates. It
is possible to opt out of auto-invalidation, as detailed in the
[Opt out of node auto-invalidation](https://developer.android.com/develop/ui/compose/custom-modifiers#autoinvalidation) section.

#### `ModifierNodeElement`

A `ModifierNodeElement` is an immutable class that holds the data to create or
update your custom modifier:


```kotlin
// ModifierNodeElement
private data class CircleElement(val color: Color) : ModifierNodeElement<CircleNode>() {
    override fun create() = CircleNode(color)

    override fun update(node: CircleNode) {
        node.color = color
    }
}
```

<br />

`ModifierNodeElement` implementations need to override the following methods:

1. `create`: This is the function that instantiates your modifier node. This gets called to create the node when your modifier is first applied. Usually, this amounts to constructing the node and configuring it with the parameters that were passed in to the modifier factory.
2. `update`: This function is called whenever this modifier is provided in the same spot this node already exists, but a property has changed. This is determined by the `equals` method of the class. The modifier node that was previously created is sent as a parameter to the `update` call. At this point, you should update the nodes' properties to correspond with the updated parameters. The ability for nodes to be reused this way is key to the performance gains that `Modifier.Node` brings; therefore, you must update the existing node rather than creating a new one in the `update` method. In our circle example, the color of the node is updated.

Additionally, `ModifierNodeElement` implementations also need to implement
`equals` and `hashCode`. `update` will only get called if an equals comparison
with the previous element returns false.

> [!WARNING]
> **Warning:** Your `ModifierNodeElement` must implement `equals` and `hashCode` correctly and not rely on instance equality. Without this, your modifier node will be updated unnecessarily and perform poorly. Use a data class to achieve this automatically.

The preceding example uses a data class to achieve this. These methods are used
to check if a node needs updating or not. If your element has properties that
don't contribute to whether a node needs to be updated, or you want to avoid
data classes for binary compatibility reasons, then you can manually implement
`equals` and `hashCode`, for example, the
[padding modifier element](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/Padding.kt;l=358).

#### Modifier factory

This is the public API surface of your modifier. Most implementations create the
modifier element and add it to the modifier chain:


```kotlin
// Modifier factory
fun Modifier.circle(color: Color) = this then CircleElement(color)
```

<br />

#### Complete example

These three parts come together to create the custom modifier to draw a circle
using the `Modifier.Node` APIs:


```kotlin
// Modifier factory
fun Modifier.circle(color: Color) = this then CircleElement(color)

// ModifierNodeElement
private data class CircleElement(val color: Color) : ModifierNodeElement<CircleNode>() {
    override fun create() = CircleNode(color)

    override fun update(node: CircleNode) {
        node.color = color
    }
}

// Modifier.Node
private class CircleNode(var color: Color) : DrawModifierNode, Modifier.Node() {
    override fun ContentDrawScope.draw() {
        drawCircle(color)
    }
}
```

<br />

## Common situations using `Modifier.Node`

When creating custom modifiers with `Modifier.Node`, here are some common
situations you might encounter.

### Zero parameters

If your modifier has no parameters, then it never needs to update and,
furthermore, doesn't need to be a data class. The following is a sample
implementation of a modifier that applies a fixed amount of padding to a
composable:


```kotlin
fun Modifier.fixedPadding() = this then FixedPaddingElement

data object FixedPaddingElement : ModifierNodeElement<FixedPaddingNode>() {
    override fun create() = FixedPaddingNode()
    override fun update(node: FixedPaddingNode) {}
}

class FixedPaddingNode : LayoutModifierNode, Modifier.Node() {
    private val PADDING = 16.dp

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val paddingPx = PADDING.roundToPx()
        val horizontal = paddingPx * 2
        val vertical = paddingPx * 2

        val placeable = measurable.measure(constraints.offset(-horizontal, -vertical))

        val width = constraints.constrainWidth(placeable.width + horizontal)
        val height = constraints.constrainHeight(placeable.height + vertical)
        return layout(width, height) {
            placeable.place(paddingPx, paddingPx)
        }
    }
}
```

<br />

### Reference composition locals

`Modifier.Node` modifiers don't automatically observe changes to Compose state
objects, like `CompositionLocal`. The advantage `Modifier.Node` modifiers have
over modifiers that are just created with a composable factory is that they can
read the value of the composition local from where the modifier is used in your
UI tree, not where the modifier is allocated, using [`currentValueOf`](https://developer.android.com/reference/kotlin/androidx/compose/ui/node/CompositionLocalConsumerModifierNode#(androidx.compose.ui.node.CompositionLocalConsumerModifierNode).currentValueOf(androidx.compose.runtime.CompositionLocal)).

However, modifier node instances don't automatically observe state changes. To
automatically react to a composition local changing, you can read its current
value inside a scope:

- `DrawModifierNode`: [`ContentDrawScope`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/DrawModifierNode.kt;l=31)
- `LayoutModifierNode`: [`MeasureScope`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/LayoutModifierNode.kt;l=64) \& [`IntrinsicMeasureScope`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/LayoutModifierNode.kt;l=87)
- `SemanticsModifierNode`: [`SemanticsPropertyReceiver`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/semantics/SemanticsProperties.kt;l=788)

This example observes the value of `LocalContentColor` to draw a background
based on its color. As `ContentDrawScope` does observe snapshot changes, this
automatically redraws when the value of `LocalContentColor` changes:


```kotlin
class BackgroundColorConsumerNode :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        val currentColor = currentValueOf(LocalContentColor)
        drawRect(color = currentColor)
        drawContent()
    }
}
```

<br />

To react to state changes outside of a scope and automatically update your
modifier, use an [`ObserverModifierNode`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/node/ObserverModifierNode.kt).

For example, [`Modifier.scrollable`](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/Scrollable.kt;l=269) uses this technique to
observe changes in `LocalDensity`. A simplified example is shown in the
following example:


```kotlin
class ScrollableNode :
    Modifier.Node(),
    ObserverModifierNode,
    CompositionLocalConsumerModifierNode {

    // Place holder fling behavior, we'll initialize it when the density is available.
    val defaultFlingBehavior = DefaultFlingBehavior(splineBasedDecay(UnityDensity))

    override fun onAttach() {
        updateDefaultFlingBehavior()
        observeReads { currentValueOf(LocalDensity) } // monitor change in Density
    }

    override fun onObservedReadsChanged() {
        // if density changes, update the default fling behavior.
        updateDefaultFlingBehavior()
    }

    private fun updateDefaultFlingBehavior() {
        val density = currentValueOf(LocalDensity)
        defaultFlingBehavior.flingDecay = splineBasedDecay(density)
    }
}
```

<br />

### Animate a modifier

`Modifier.Node` implementations have access to a `coroutineScope`. This allows
for use of the [Compose Animatable APIs](https://developer.android.com/develop/ui/compose/animation/value-based#animatable). For example, this snippet modifies
the `CircleNode` shown previously to fade in and out repeatedly:

> [!NOTE]
> **Note:** `Modifier.Node` can be reused in a composable. You can set the new state either by setting it in [`onAttach`](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier.Node#onAttach()), or by resetting in [`onReset`](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier.Node#onReset()).


```kotlin
class CircleNode(var color: Color) : Modifier.Node(), DrawModifierNode {
    private lateinit var alpha: Animatable<Float, AnimationVector1D>

    override fun ContentDrawScope.draw() {
        drawCircle(color = color, alpha = alpha.value)
        drawContent()
    }

    override fun onAttach() {
        alpha = Animatable(1f)
        coroutineScope.launch {
            alpha.animateTo(
                0f,
                infiniteRepeatable(tween(1000), RepeatMode.Reverse)
            ) {
            }
        }
    }
}
```

<br />

### Share state between modifiers using delegation

`Modifier.Node` modifiers can delegate to other nodes. There are many use cases
for this, such as extracting common implementations across different modifiers,
but it can also be used to share common state across modifiers.

For example, a basic implementation of a clickable modifier node that shares
interaction data:


```kotlin
class ClickableNode : DelegatingNode() {
    val interactionData = InteractionData()
    val focusableNode = delegate(
        FocusableNode(interactionData)
    )
    val indicationNode = delegate(
        IndicationNode(interactionData)
    )
}
```

<br />

### Opt out of node auto-invalidation

`Modifier.Node` nodes automatically invalidate when their corresponding
`ModifierNodeElement` calls update. For complex modifiers, you might want to opt
out of this behavior to gain more fine-grained control over when your modifier
invalidates phases.

This is particularly useful if your custom modifier modifies both layout and
draw. Opting out of auto-invalidation lets you just invalidate draw when only
draw-related properties, such as `color`, change. This avoids invalidating
layout and can improve your modifier's performance.

A hypothetical example of this is shown in the following example with a modifier
that has a `color`, `size`, and `onClick` lambda as properties. This modifier
invalidates only what is required, skipping any unneeded invalidation:


```kotlin
class SampleInvalidatingNode(
    var color: Color,
    var size: IntSize,
    var onClick: () -> Unit
) : DelegatingNode(), LayoutModifierNode, DrawModifierNode {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private val clickableNode = delegate(
        ClickablePointerInputNode(onClick)
    )

    fun update(color: Color, size: IntSize, onClick: () -> Unit) {
        if (this.color != color) {
            this.color = color
            // Only invalidate draw when color changes
            invalidateDraw()
        }

        if (this.size != size) {
            this.size = size
            // Only invalidate layout when size changes
            invalidateMeasurement()
        }

        // If only onClick changes, we don't need to invalidate anything
        clickableNode.update(onClick)
    }

    override fun ContentDrawScope.draw() {
        drawRect(color)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val size = constraints.constrain(size)
        val placeable = measurable.measure(constraints)
        return layout(size.width, size.height) {
            placeable.place(0, 0)
        }
    }
}
```

<br />