---
target: Swing client views
total_score: 23
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 2
target_identity: "file:/home/an7ony26/Connect4/frontend/src/main/java/com/lso/view"
timestamp: 2026-09-24T23-02-24Z
slug: frontend-src-main-java-com-lso-view
---
Method: dual-agent (A: design review · B: detector + deterministic measurements)

## Design Health Score

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of system status | 2 | No "your turn" text in game; only the opponent name dimmed (3.08:1); no last-move marker |
| 2 | Match system / real world | 3 | Frameless board, red/green instead of red/yellow, "Col 1…7", "Opponent is" column |
| 3 | User control and freedom | 2 | No Esc on boxes; Game Over forces Rematch/Leave room while the glass pane blocks the tabs |
| 4 | Consistency and standards | 2 | game/room mixed, Title vs sentence case, double-click only in My Games, FlatLaf blue off palette |
| 5 | Error prevention | 3 | Good confirmations and click guard; Join Selected always enabled, Col buttons enabled off-turn |
| 6 | Recognition rather than recall | 3 | Icon-only bell with no tooltip, keys 1-7 never shown, join request doesn't name the room |
| 7 | Flexibility and efficiency | 2 | Keys 1-7 and board click exist, but no button is focusable: no box answerable by keyboard |
| 8 | Aesthetic and minimalist | 2 | Calm but flat hierarchy (everything bold 18-20), no primary action, Col row takes a full band |
| 9 | Error recovery | 3 | Plain-language ErrorText; title always "Error", INVALID_NAME ambiguous |
| 10 | Help and documentation | 1 | Zero tooltips, name rules shown only after a refusal |
| **Total** | | **23/40** | **Acceptable** |

## Design Specificity Verdict

LLM: only the board is Connect-4 specific and it is weak (42 grey holes, no frame, cells width/7 x height/6: 74px horizontal vs 10px vertical gaps). Everything else (edge-to-edge tables, text-only buttons, one tan card for win/error/delete) is category-interchangeable. The warm tan/brown palette is the real authored asset, diluted by FlatLaf blue (tab underline, row selection) and by ERROR_RED/SUCCESS_GREEN as player colours.

Deterministic scan: `impeccable detect` returned [] exit 0 because it scans web sources, not .java. Substitute measurements (component tree + PNG sampling) confirmed A and added: red disc vs empty hole 1.74:1; selected row with table focused #BBBBBB on #4B6EAF 2.64:1; selected tab only an underline at 1.89:1; normal/rollover/pressed button renders pixel-identical. Browser overlay: n/a (desktop app).

## Priority Issues

1. [P1] Turn state and last move invisible in game. Fix: "Your turn"/"Bruno's turn" line in the existing strip; setEnabled(canPlay(col)) on Col buttons; ring on last move in BoardView. /impeccable clarify
2. [P1, P0 for keyboard users] Buttons have no states and no keyboard access (UiUtil.java:97-100). Fix: JButton.buttonType=borderless, drop setFocusable(false), primary FlatLaf.style on OK/Accept/Rematch/Create, Enter/Esc in OverlayPanel. /impeccable harden
3. [P2] Colour semantics collide, FlatLaf blue leaks (selection 2.64:1, tab 1.89:1, red disc vs hole 1.74:1, red/green 1.98:1 deuteranopia, green = player 2 and "your turn"). Fix: @accentColor global extra default, red+yellow players, darker holes, tab dot in own disc colour. /impeccable colorize
4. [P2] Board without identity; win is a generic modal covering the board, no winning four highlighted. Fix: square cells + slab, winning line, card anchored lower / lighter dim, result-specific title. /impeccable delight
5. [P2] Lobby IA: actions detached from tables, mute empty state, Join always enabled, no gutter, copy inconsistencies, zero tooltips. /impeccable layout + /impeccable clarify

## Persona Red Flags

Alex (power user, 5 games): no Tab to buttons, every box needs the mouse; background strip not clickable and gone after 6s; no last-move marker.
Jordan (first-timer): name rules only after refusal; empty lobby gives no path; no "Your turn" text; Home/Abandon/Leave room/Delete Room unclear.
Sam (keyboard-only, low vision): cannot create/join/accept/rematch/dismiss by keyboard; turn shown by opacity only; idle bell 1.79:1, no accessible name.

## Minor Observations

- Login: "Username" + "Choose a username:" redundant; no placeholder, no 20-char cap.
- INVALID_NAME copy reads as "no digits".
- Join request doesn't name the room.
- Box title always "Error"; "Game Over" used for 3 different boxes.
- "Arial" hard-coded 3x (falls back to DejaVu), tabs/tables use FlatLaf font: two type systems; box title and message same size.
- Col buttons could read "1".."7" or go away (board is clickable).
- Disabled buttons keep the hand cursor.
- 12 distinct spacing values off-grid; lobby has no outer padding.

## Questions to Consider

- What if the board itself showed whose turn it is (slab border in the mover's colour)?
- With 5 games at once, is the lobby's first job browsing rooms or "where is it my turn"?
- Does a win deserve the same card as "Delete room?"?
