package com.lso.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

// A dialog drawn inside the main window, used as its glass pane. Unlike a
// JOptionPane it is not a separate window, so it can't end up behind the main
// one, and its content can be replaced while it is open (for example
// "Rematch / Leave Room" -> "Waiting for the opponent...").
public class OverlayPanel extends JPanel
{
    // For this long after it opens or changes, a box ignores its buttons. The
    // second click of a double click would otherwise hit the button that is
    // now under the mouse: after Rematch, "Home" is almost where Rematch
    // was. A box that pops up under a click is protected too.
    public static final int CLICK_GUARD_MILLIS = 300;

    private long openedAt; // System.nanoTime() of the last open()
    private Component focusBefore; // what had the focus before the box opened

    private JLabel titleLabel;
    private JLabel messageLabel;
    private JTextField inputField;
    private JPanel buttonPanel;

    public OverlayPanel()
    {
        setLayout(new GridBagLayout());
        setOpaque(false);

        // The focus comes into the box when it opens (see open), so the keys
        // can't reach the buttons behind it, and Tab goes round its own only.
        setFocusCycleRoot(true);

        // A glass pane only intercepts the mouse if it listens to it,
        // otherwise the clicks would go through to the components below.
        addMouseListener(new MouseAdapter() {});
        addMouseMotionListener(new MouseMotionAdapter() {});
        addMouseWheelListener(e -> {});

        titleLabel = UiUtil.createStyledLabel("");
        titleLabel.setForeground(UiUtil.ACCENT);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        messageLabel = UiUtil.createStyledLabel("");
        messageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        inputField = new JTextField(20);
        inputField.setBackground(UiUtil.BACKGROUND_GRAY);
        inputField.setForeground(Color.WHITE);
        inputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiUtil.ACCENT, 2),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        inputField.setFont(inputField.getFont().deriveFont(18f));
        inputField.setCaretColor(Color.WHITE);
        inputField.setMaximumSize(inputField.getPreferredSize());
        inputField.setAlignmentX(Component.CENTER_ALIGNMENT);

        buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(UiUtil.BACKGROUND_BLACK);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiUtil.ACCENT, 2),
            BorderFactory.createEmptyBorder(30, 60, 25, 60)
        ));

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(20));
        card.add(messageLabel);
        card.add(Box.createVerticalStrut(20));
        card.add(inputField);
        card.add(Box.createVerticalStrut(15));
        card.add(buttonPanel);

        add(card);
    }

    // Dims what is behind the card.
    @Override
    protected void paintComponent(Graphics g)
    {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    // A button of a box: its text, and what a click on it does.
    public record Choice(String text, Runnable action) {}

    // A message with a row of buttons. Calling it again replaces the content.
    public void showChoice(String title, String message, Choice... choices)
    {
        JButton[] buttons = new JButton[choices.length];
        for(int i = 0; i < choices.length; i++)
        {
            buttons[i] = button(choices[i]);
        }
        open(title, message, null, buttons);
    }

    // A message with a text field, which starts with 'text' (so a name that
    // was refused can be corrected instead of typed again). onSubmit gets the
    // typed text, from the submit button or from Enter.
    public void showInput(String title, String message, String text,
                          String submitText, Consumer<String> onSubmit, Choice cancel)
    {
        JButton submit = button(new Choice(submitText, () -> onSubmit.accept(inputField.getText())));
        open(title, message, text, submit, button(cancel));
        inputField.addActionListener(e -> onSubmit.accept(inputField.getText()));
    }

    // Clicks that come right after the box opened or changed are ignored
    // (CLICK_GUARD_MILLIS).
    private JButton button(Choice choice)
    {
        JButton button = UiUtil.createStyledButton(choice.text());
        button.addActionListener(e ->
        {
            if(!justOpened())
            {
                choice.action().run();
            }
        });
        return button;
    }

    public void close()
    {
        setVisible(false);
        if(focusBefore != null)
        {
            focusBefore.requestFocusInWindow();
        }
    }

    // A long message wraps at a fixed width instead of stretching the card.
    private static String wrap(String message)
    {
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='width: 370px; text-align: center'>" + escaped + "</body></html>";
    }

    private boolean justOpened()
    {
        return System.nanoTime() - openedAt < CLICK_GUARD_MILLIS * 1_000_000L;
    }

    // 'inputText' is what the text field starts with, or null for a box
    // without one.
    private void open(String title, String message, String inputText, JButton... buttons)
    {
        boolean withInput = (inputText != null);

        // Where the focus goes back when the box closes: not a button of a
        // box that this one replaces.
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if(owner != null && !SwingUtilities.isDescendingFrom(owner, this))
        {
            focusBefore = owner;
        }

        openedAt = System.nanoTime();
        titleLabel.setText(title);
        messageLabel.setText(wrap(message));

        // The Enter handler of a previous showInput must not survive.
        for(ActionListener listener : inputField.getActionListeners())
        {
            inputField.removeActionListener(listener);
        }
        inputField.setText(withInput ? inputText : "");
        inputField.setVisible(withInput);

        buttonPanel.removeAll();
        for(JButton button : buttons)
        {
            buttonPanel.add(button);
        }

        setVisible(true);
        revalidate();
        repaint();

        // The first button, so a box puts the harmless choice first: a key
        // press is enough to activate it.
        JComponent focus = withInput ? inputField : buttons[0];
        SwingUtilities.invokeLater(focus::requestFocusInWindow);
    }
}
