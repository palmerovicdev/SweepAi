# Jetbrains Extension for Sweep
Open in IntelliJ and click Run Plugin on the top right corner.
And then download required packages.

## External Agent Chat (OpenCode / Codex)

The chat can delegate a conversation to an external CLI agent instead of the
Sweep cloud backend. Configure it under **Settings → Sweep AI → Chat Provider**
by picking:

- **OpenCode** — spawns `opencode serve` locally (or points at an existing base
  URL) and talks HTTP + SSE. Authenticate once with `opencode auth login <provider>`
  in a terminal.
- **Codex** — spawns `codex app-server --listen stdio://` per conversation and
  talks JSON-RPC 2.0 over stdio. Authenticate once with `codex login` in a
  terminal.

The active provider fully owns the agent loop and its tools; Sweep observes tool
calls with an "Executed by …" badge in the tooltip. See
`docs/plans/external-agents-chat-implementation.md` for the full architecture.

## Customizing Autocomplete Keystrokes

The autocomplete accept and reject keystrokes are fully customizable via IntelliJ's keymap settings:

### Default Keystrokes
- **Accept Completion**: `Tab`
- **Reject Completion**: `Escape`

### How to Customize
1. Open **Settings/Preferences** → **Keymap**
2. Search for **"Accept Edit Completion"** or **"Reject Edit Completion"**
3. Right-click on the action and select **"Add Keyboard Shortcut"**
4. Assign your preferred keystroke (e.g., `Enter`, `Ctrl+Space`, etc.)
5. The plugin will automatically adapt to your custom keystrokes without requiring a restart

**Note**: The keystroke must map to a standard editor action (like TAB, ENTER, ESCAPE, arrow keys, etc.) to be intercepted reliably. Custom key combinations that don't correspond to editor actions may not work.

## IdeaVim Integration

For IdeaVim users, put in your `~/.ideavimrc`:

```vim
sethandler <Tab> a:ide
```

Or if it doesn't work:

```vim
map <Tab> :action dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction<CR>
```

You can also customize the IdeaVim mapping to use different keys:

```vim
" Use Enter to accept completions
map <CR> :action dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction<CR>

" Use Ctrl+Y to reject completions
map <C-y> :action dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction<CR>
```
