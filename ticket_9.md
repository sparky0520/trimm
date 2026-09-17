# Ticket 9: Implement AI Backend Connection (Chat.tsx)

## Description
The Chat agent currently connects to a hardcoded local SSE server at `http://localhost:8787/v1/chat/completion`. This backend logic appears to be a stub or relies on an external worker that hasn't been implemented yet.

## Requirements
- Implement the actual AI backend worker/script.
- Replace the hardcoded localhost URL with the appropriate environment variable or configuration for production and development.
- Ensure the backend properly handles the prompt history and dispatches Tool Calls (`add_clip`, `add_text`, etc.) over Server-Sent Events.
