# Editing a lorebook

This sheet manages one book and its entry list.

## Book-level fields

- **Name**: for organization.
- **Description**: optional note for humans.
- **Enabled**: turns the whole book on or off.
- **Recursive scanning**: lets already triggered entries activate more entries in later passes.
- **Token budget**: limits how much text from this book can be inserted.

## Recommended workflow

1. Create the book.
2. Set whether it should be recursive.
3. Decide whether it needs a token budget.
4. Add entries one by one.
5. Test with real chat examples.

## Demo workflow

```text
Book:
School Life

Use case:
Inject campus details only when class, club, dorm, or exam topics appear.

Suggested setup:
- Recursive scanning: off at first
- Token budget: leave empty first
- Add separate entries for dorm, student council, exam rules, and club culture
```

## When to change recursive scanning

Turn it on only when one triggered entry should be able to wake up related entries in later passes.

> Most books should start simple: recursive scanning off, no token budget, then tighten later if needed.
