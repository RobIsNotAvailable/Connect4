package com.lso.view;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.lso.view.OverlayPanel.Choice;

// The boxes drawn over the window (OverlayPanel), one at a time. What
// interrupts the player (a join request, an error) must not replace a box that
// is already open: a Game Over swallowed by a notice would leave the player
// without Rematch / Leave room. So a notice waits in a queue until the overlay
// is free, and a join request also waits for the lobby. A box the player has
// to answer is shown at once instead, and a notice it replaces goes back to
// the front of the queue.
public class Dialogs
{
    // 'joinRoom' is the id of the room when the notice is a join request, which
    // the joiner can withdraw (JOIN_CANCELLED), otherwise null.
    private record Notice(String joinRoom, String title, String message, Choice[] choices) {}

    private final OverlayPanel overlay;
    private final BooleanSupplier inGame; // a game is on screen: join requests wait
    private final Deque<Notice> pending = new ArrayDeque<>();
    private Notice shown;

    public Dialogs(OverlayPanel overlay, BooleanSupplier inGame)
    {
        this.overlay = overlay;
        this.inGame = inGame;
    }

    // A message with OK, in the queue.
    public void notice(String title, String message)
    {
        queue(new Notice(null, title, message, new Choice[] {new Choice("OK", this::close)}));
    }

    // A request to join room 'roomId', in the queue until the lobby is shown.
    public void joinRequest(String roomId, String title, String message, Choice... choices)
    {
        queue(new Notice(roomId, title, message, choices));
    }

    // The joiner gave up: the box must not stay on screen or wait in the queue.
    public void cancelJoinRequest(String roomId)
    {
        pending.removeIf(n -> roomId.equals(n.joinRoom()));
        if(shown != null && roomId.equals(shown.joinRoom()))
        {
            close();
        }
    }

    // A box to answer now.
    public void show(String title, String message, Choice... choices)
    {
        displace();
        overlay.showChoice(title, message, choices);
    }

    // The same, with a text field (see OverlayPanel.showInput).
    public void ask(String title, String message, String text, String submitText, Consumer<String> onSubmit, Choice cancel)
    {
        displace();
        overlay.showInput(title, message, text, submitText, onSubmit, cancel);
    }

    // Nothing else matters any more (the connection is lost): this box takes
    // the place of whatever is on screen, and the queue is dropped.
    public void showOnly(String title, String message, Choice... choices)
    {
        pending.clear();
        shown = null;
        overlay.showChoice(title, message, choices);
    }

    // Every box is closed through here, so the next notice in line appears.
    public void close()
    {
        overlay.close();
        shown = null;
        showNext();
    }

    // A notice on screen goes back to the front of the queue, to come back
    // once the overlay is free again.
    public void displace()
    {
        if(shown != null)
        {
            pending.addFirst(shown);
            shown = null;
        }
    }

    // A box is on screen. It stops the mouse but not the keys 1-7, which are
    // bound to the whole window.
    public boolean isOpen()
    {
        return overlay.isVisible();
    }

    private void queue(Notice notice)
    {
        pending.add(notice);
        showNext();
    }

    private void showNext()
    {
        if(overlay.isVisible())
        {
            return;
        }

        for(Iterator<Notice> it = pending.iterator(); it.hasNext();)
        {
            Notice next = it.next();
            if(next.joinRoom() == null || !inGame.getAsBoolean())
            {
                it.remove();
                shown = next;
                overlay.showChoice(next.title(), next.message(), next.choices());
                return;
            }
        }
    }
}
