package com.lso.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

// A dialog drawn inside the main window, used as its glass pane. Unlike a
// JOptionPane it is not a separate window, so it can't end up behind the main
// one, and its content can be replaced while it is open (for example
// "Rematch / Leave room" -> "Waiting for the opponent...").
public class OverlayPanel extends JPanel
{
    // For this long after it opens or changes, a box ignores its buttons. The
    // second click of a double click would otherwise hit the button that is
    // now under the mouse: after Rematch, "Leave room" is almost where
    // Rematch was. A box that pops up under a click is protected too.
    public static final int CLICK_GUARD_MILLIS = 300;

    private long openedAt; // System.nanoTime() of the last open()

    private JLabel titleLabel;
    private JLabel messageLabel;
    private JTextField inputField;
    private JPanel buttonPanel;

    public OverlayPanel()
    {
        setLayout(new GridBagLayout());
        setOpaque(false);

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
        UiUtil.styleComponent(inputField);
        inputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UiUtil.ACCENT, 2),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        inputField.setFont(new Font("Arial", Font.PLAIN, 18));
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

    // A message with a row of buttons. onChoice gets the index of the button
    // that was clicked. Calling it again replaces the content.
    public void showChoice(String title, String message, String[] buttonTexts, IntConsumer onChoice)
    {
        JButton[] buttons = new JButton[buttonTexts.length];

        for(int i = 0; i < buttonTexts.length; i++)
        {
            final int index = i;
            buttons[i] = UiUtil.createStyledButton(buttonTexts[i]);
            UiUtil.addListener(buttons[i], e ->
            {
                if(!justOpened())
                {
                    onChoice.accept(index);
                }
            });
        }

        open(title, message, null, buttons);
    }

    // A message with a text field, which starts with 'text' (so a name that
    // was refused can be corrected instead of typed again). onSubmit gets the
    // typed text, from the submit button or from Enter; onCancel runs when
    // the other button is clicked.
    public void showInput(String title, String message, String text,
                          String submitText, Consumer<String> onSubmit,
                          String cancelText, Runnable onCancel)
    {
        JButton submitButton = UiUtil.createStyledButton(submitText);
        UiUtil.addListener(submitButton, e ->
        {
            if(!justOpened())
            {
                onSubmit.accept(inputField.getText());
            }
        });

        JButton cancelButton = UiUtil.createStyledButton(cancelText);
        UiUtil.addListener(cancelButton, e ->
        {
            if(!justOpened())
            {
                onCancel.run();
            }
        });

        open(title, message, text, submitButton, cancelButton);
        inputField.addActionListener(e -> onSubmit.accept(inputField.getText()));
    }

    public void close()
    {
        setVisible(false);
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

        if(withInput)
        {
            SwingUtilities.invokeLater(() -> inputField.requestFocusInWindow());
        }
    }
}
