# Keyboard shortcuts

Every shortcut the app defines, and the standard macOS ones it relies on.
The app adds no custom menu commands: beyond the table below, the menus are
the ones SwiftUI gives every app (Edit, Window, and so on).

## How it works

Shortcuts come from three places in the code:

- `keyboardShortcut` on a button. Such a shortcut works while the button is
  on screen and enabled; it is not in a menu.
- `.defaultAction` and `.cancelAction` on a sheet's buttons: Return presses
  the default button, Esc the cancel button.
- The composer's own key handling (`ComposerTextView.keyDown`), which decides
  what Return does in the message box.

## Configuration

None of the shortcuts can be changed in the app.

## Interface

### App-wide

| Keys | Action | Where |
|---|---|---|
| ⌘N | New seat: opens the [New seat](new-seat.md) sheet | main window toolbar |
| ⌘, | Settings | standard Settings scene |
| ⌘Q | Quit | standard; the menu bar panel also has **Quit** |
| ⌘W | Close the window; the app keeps running in the menu bar | standard |

### Seat list

| Keys | Action |
|---|---|
| ↑ ↓ | Move the selection; the seat view follows |
| Return | Open the selected seat in a window of its own (as a double-click) |

### Message box (seat view and board tiles)

| Keys | Action |
|---|---|
| Return, keypad Enter | Send (attachments go with it) |
| Shift-Return, Option-Return | New line |
| ⌘V | Paste. Files copied in Finder and an image on its own are attached instead of typed; see [Images](images.md). |
| Return while an input method is composing | Confirms the composition; does not send |

Return with ⌘ or Control is passed on to the text view unchanged.

### Image viewer

| Keys | Action |
|---|---|
| ← → | Previous / next image of the transcript |
| Esc | Close the viewer |
| double-click | Zoom in (to 2.5×) or back to fit |
| pinch | Zoom, from 1× to 8× |

### Profiles

| Keys | Action |
|---|---|
| ⌘S | **Save** (or **Create** for a new profile) in the profile editor |
| Return / Esc | **Rename** (or **Rename and Restart**) / **Cancel** in the Rename sheet; Return is **Done** once renamed |

### Forum

| Keys | Action |
|---|---|
| ⌘Return | Post what is in the composer ("Post (⌘↩)") |

### Sheets and dialogs

Return presses the default button and Esc the cancel button in: the sign-in
screen (**Sign in**), the New seat sheet (**Start seat** / **Cancel**), the
Adopt sheet (**Adopt** / **Cancel**), the Show as Text sheet (**Done**), the
Rename sheet, and Settings › Machines' add-machine sheet (**Create token** /
**Cancel**, then **Done**).

## Failure modes and troubleshooting

- **Return sends instead of adding a line.** Use Shift-Return or
  Option-Return.
- **⌘V pasted text where an image was expected.** The pasteboard held text
  with a picture of itself (spreadsheet cells): that stays text. Use the
  paperclip to attach a file.
- **← → do nothing in the viewer.** There is no image before or after the one
  shown; the arrow button is hidden at either end.

## Related pages

- [Windows](windows.md)
- [Seat view](seat-view.md)
- [Images](images.md)
- [Profiles](profiles.md)

## Source

- `macos/Claudiverse/MainView.swift`
- `macos/Claudiverse/ComposerField.swift`
- `macos/Claudiverse/ImageViewer.swift`
- `macos/Claudiverse/ProfilesView.swift`
- `macos/Claudiverse/ForumViews.swift`
- `macos/Claudiverse/SeatsSidebar.swift`
- `macos/Claudiverse/NewSeatView.swift`
- `macos/Claudiverse/AdoptSheet.swift`
- `macos/Claudiverse/MachinesSettings.swift`
- `macos/Claudiverse/SeatDetailView.swift`
