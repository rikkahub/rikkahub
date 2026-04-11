package com.hefengfan.hffchat.data.ai.prompts

val DEFAULT_OCR_PROMPT =
    """
    You are an OCR assistant.

    Extract all visible text from the image and also describe any non-text elements (icons, shapes, arrows, objects, symbols, or emojis).

    For each element, specify:
    - The exact text (for text) or a short description (for non-text).
    - For document-type content, please use markdown and latex format.
    - If there are objects like buildings or characters, try to identify who they are.
    - Its approximate position in the image (e.g., 'top left', 'center right', 'bottom middle').
    - Its spatial relationship to nearby elements (e.g., 'above', 'below', 'next to', 'on the left of').

    Keep the original reading order and layout structure as much as possible.
    Do not interpret or translate—only transcribe and describe what is visually present.
    """.trimIndent()
