# Jetbrains Extension for Sweep
Open in IntelliJ and click Run Plugin on the top right corner.
And then download required packages.

## External Agent Chat (OpenCode / Codex)

The chat can delegate a conversation to an external agent instead of the
Sweep cloud backend. Configure it under **Settings → Sweep AI → Chat Provider**
by picking:

- **Codex** — powered by [`@openai/codex-sdk`](https://www.npmjs.com/package/@openai/codex-sdk). Authenticate once with `codex login` in a terminal.
- **OpenCode** — powered by [`@opencode-ai/sdk`](https://www.npmjs.com/package/@opencode-ai/sdk). Authenticate once with `opencode auth login <provider>` in a terminal.

Both providers run inside a bundled Node.js sidecar (`ai-bridge`). On first use
the plugin extracts the sidecar to `${PLUGINS_DIR}/sweep-ai-bridge/<version>/`
and installs the SDKs via `npm install --omit=dev`. Prerequisite: **Node.js 18+**
on your machine; the plugin auto-detects it on PATH (including `nvm`, `volta`,
`fnm`, `asdf` install locations) or you can pin an absolute path in the settings
tab.

The active provider fully owns the agent loop and its tools; Sweep observes tool
calls with an "Executed by …" badge in the tooltip. Chat toolbar exposes three
provider-aware pills so you can flip provider / approval-or-agent / reasoning
effort mid-conversation without a restart — pill changes are propagated to the
daemon on the next message.

### Managing SDK versions

Under **Settings → Sweep AI → Chat Provider → SDK dependencies** you can:

- See the installed version of each SDK.
- Pin a specific semver spec (`latest`, `^0.5.0`, `0.5.2`, …).
- Click **Update all** to pull the latest versions.
- Click **Reinstall from scratch** to wipe `node_modules/` and reinstall clean.

`npm install` runs in the background with a live log; the bridge daemon is
restarted automatically when the install completes.

See `docs/plans/humming-waddling-ritchie.md` and
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
