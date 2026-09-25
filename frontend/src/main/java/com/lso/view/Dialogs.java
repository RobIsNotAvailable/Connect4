package com.lso.view;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import com.lso.view.OverlayPanel.Choice;

// The boxes drawn over the window (OverlayPanel), one at a time. What
// interrupts the player (a join request, an error) must not replace a box that
// is already open: a Game Over swallowed by a notice would leave the player
// without Rematch / Leave Room. So a notice waits in a queue until the overlay
// is free, and a join request also waits for the lobby. A box the player has
// to answer is shown at once instead, and a notice it replaces goes back to
// the front of the queue. During a game the bell of the board shows how many
// join requests wait, and opens the first one (showJoinRequest).
public class Dialogs
{
    // 'joinRoom' is the id of the room when the notice is a join request, which
    // the joiner can withdraw (JOIN_CANCELLED), otherwise null.
    private record Notice(String joinRoom, String title, String message, Choice[] choices) {}

    private final OverlayPanel overlay;
    private final BooleanSupplier inGame; // a game is on screen: join requests wait
    private final Deque<Notice> pending = new ArrayDeque<>();
    private Notice shown;
    private IntConsumer onJoinRequests = count -> {};

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

    // 'listener' is told how many join requests wait in the queue, whenever
    // that changes.
    public void onJoinRequests(IntConsumer listener)
    {
        this.onJoinRequests = listener;
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
        joinRequestsChanged();
    }

    // The first join request of the queue, now, even during a game (the bell).
    // Nothing happens while another box is open.
    public void showJoinRequest()
    {
        showFirst(n -> n.joinRoom() != null);
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
        joinRequestsChanged();
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
            joinRequestsChanged();
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
        joinRequestsChanged();
        showNext();
    }

    private void showNext()
    {
        showFirst(n -> n.joinRoom() == null || !inGame.getAsBoolean());
    }

    // Shows the first notice of the queue that 'which' accepts, unless a box
    // is already open.
    private void showFirst(Predicate<Notice> which)
    {
        if(overlay.isVisible())
        {
            return;
        }

        for(Iterator<Notice> it = pending.iterator(); it.hasNext();)
        {
            Notice next = it.next();
            if(which.test(next))
            {
                it.remove();
                shown = next;
                overlay.showChoice(next.title(), next.message(), next.choices());
                joinRequestsChanged();
                return;
            }
        }
    }

    private void joinRequestsChanged()
    {
        onJoinRequests.accept((int) pending.stream().filter(n -> n.joinRoom() != null).count());
    }
}
