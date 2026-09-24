package com.lso.view;


import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTableHeaderUI;
import javax.swing.table.JTableHeader;
import java.awt.event.ActionEvent;

public class UiUtil
{
    public static final Color BACKGROUND_BLACK = new Color(35, 35, 35);

    public static final Color BACKGROUND_GRAY = new Color(84, 84, 84, 255);

    public static final Color BACKGROUND_BAR = new Color(46, 46, 46);

    public static final Color ACCENT = new Color(200,165,140);

    public static final Color ACCENT_SECONDARY = new Color(107, 88, 75);

    public static final Color ERROR_RED = new Color (224, 58, 58);

    public static final Color SUCCESS_GREEN = new Color (58, 224, 97);

    // The colour of the discs of player 1 or 2.
    public static Color playerColor(int player)
    {
        return (player == 1) ? ERROR_RED : SUCCESS_GREEN;
    }

    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static JButton createStyledButton(String text)
    {
        JButton button = new JButton(text);
        button.setFont(new Font("Arial", Font.BOLD, 18));
        button.setForeground(Color.WHITE);

        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(10, 15, 15, 15));

        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        return button;
    }

    public static JLabel createStyledLabel(String text)
    {
        JLabel label = new JLabel(text, SwingConstants.CENTER);

        label.setForeground(Color.WHITE);
        label.setFont(new Font("Arial", Font.BOLD, 20));

        return label;
    }

    // A line of text on a coloured strip (see setStrip). It is always there,
    // blank and see-through when there is nothing to say, so the layout
    // around it does not change when a message appears.
    public static JLabel createStripLabel(Color background, Color foreground)
    {
        JLabel label = createStyledLabel(" ");
        label.setFont(label.getFont().deriveFont(16f));
        label.setForeground(foreground);
        label.setBackground(background);
        label.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));
        return label;
    }

    // Shows 'text' on a strip, or blanks it when 'text' is null.
    public static void setStrip(JLabel strip, String text)
    {
        strip.setText(text != null ? text : " ");
        strip.setOpaque(text != null);
        strip.repaint();
    }


    // A little person (head and shoulders) drawn with shapes instead of an
    // image file, so it can take any colour and stays sharp at any size.
    public static class PersonIcon implements Icon
    {
        private final int size;
        private final Color color;

        public PersonIcon(int size)
        {
            this(size, UiUtil.ACCENT);
        }

        public PersonIcon(int size, Color color)
        {
            this.size = size;
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(color);

            int headSize = size * 5 / 12;
            int shouldersTop = headSize + size / 12;

            g2d.fillOval(x + (size - headSize) / 2, y, headSize, headSize);

            // The upper half of an ellipse as wide as the icon: the arc is
            // twice as tall as the space left for the shoulders.
            g2d.fillArc(x, y + shouldersTop, size, (size - shouldersTop) * 2, 0, 180);
            g2d.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return size;
        }

        @Override
        public int getIconHeight()
        {
            return size;
        }
    }

    public static class DotIcon implements Icon
    {
        private final Color color;
        private final int size;

        public DotIcon(Color color, int size)
        {
            this.color = color;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(color);

            g2d.fillOval(x, y, size, size);
            g2d.dispose();
        }

        @Override
        public int getIconWidth() 
        { 
            return size; 
        }
        
        @Override
        public int getIconHeight() 
        { 
            return size; 
        }
    }

    // A list of rooms or games: one row can be selected, and the first column
    // holds the id, which the code reads but the player does not see.
    public static class TransparentTable extends JTable
    {
        private final String[] columnNames;

        public TransparentTable(String... columnNames)
        {
            super(new Object[0][columnNames.length], columnNames);
            this.columnNames = columnNames;

            setFillsViewportHeight(true);
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(20f));
            setRowHeight(30);
            setShowGrid(false);
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

            // The id stays in the model, so its values can be read, but it is
            // not shown. The columns are kept when the data is replaced
            // (setRows), otherwise a new model would bring it back.
            setAutoCreateColumnsFromModel(false);
            removeColumn(getColumnModel().getColumn(0));

            JTableHeader header = getTableHeader();
            header.setBackground(UiUtil.ACCENT_SECONDARY);
            header.setPreferredSize(new Dimension(10, 10));
            header.setEnabled(false);
            header.setBorder(BorderFactory.createLineBorder(UiUtil.ACCENT_SECONDARY));

            header.setUI(new BasicTableHeaderUI()
            {
                @Override
                public void paint(Graphics g, JComponent c)
                {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setColor(UiUtil.ACCENT_SECONDARY);
                    g2d.fillRect(0, 0, c.getWidth(), c.getHeight());
                }
            });

            setTableHeader(header);
        }

        // The rows only display what the server sent: a double click must
        // not turn a cell into a text field.
        @Override
        public boolean isCellEditable(int row, int column)
        {
            return false;
        }

        // New rows mean a new model, which clears the selection: the row with
        // the same id is selected again, if it is still there. The lists are
        // sent again whenever anything changes, and the player must not lose
        // the row they picked.
        public void setRows(Object[][] rows)
        {
            int row = getSelectedRow();
            Object selectedId = (row != -1) ? getCellValue(row, 0) : null;

            setModel(new javax.swing.table.DefaultTableModel(rows, columnNames));

            for(int i = 0; i < rows.length; i++)
            {
                if(rows[i][0].equals(selectedId))
                {
                    setRowSelectionInterval(i, i);
                    break;
                }
            }
        }

        // Reads the model, not the view: unlike getValueAt it does not depend
        // on which columns are hidden.
        public Object getCellValue(int row, int modelColumn)
        {
            return getModel().getValueAt(row, modelColumn);
        }
    }

    public static class TransparentScrollPanel extends JScrollPane
    {
        public TransparentScrollPanel(Component component, int width, int height)
        {
            super(component);

            setPreferredSize(new Dimension(width, height));
            setOpaque(false);
            getViewport().setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());

            setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            JScrollBar verticalScrollBar = getVerticalScrollBar();
            verticalScrollBar.setOpaque(false);
            verticalScrollBar.setUnitIncrement(20);

            verticalScrollBar.setUI(new BasicScrollBarUI()
            {
                private final int FIXED_THUMB_HEIGHT = 50;

                @Override
                protected void configureScrollBarColors()
                {
                    this.thumbColor = UiUtil.ACCENT;
                    this.trackColor = new Color(0, 0, 0, 0);
                }

                @Override
                protected JButton createDecreaseButton(int orientation)
                {
                    return createHiddenButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation)
                {
                    return createHiddenButton();
                }

                private JButton createHiddenButton()
                {
                    JButton btn = new JButton();
                    btn.setPreferredSize(new Dimension(0, 0));
                    btn.setVisible(false);
                    return btn;
                }

                @Override
                protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds)
                {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2d.setColor(thumbColor);

                    JScrollBar scrollbar = (JScrollBar) c;
                    int maxScroll = scrollbar.getMaximum() - scrollbar.getVisibleAmount();
                    int currentScroll = scrollbar.getValue();

                    float scrollRatio = (float) currentScroll / maxScroll;
                    int availableHeight = c.getHeight() - FIXED_THUMB_HEIGHT;
                    int newY = (int) (scrollRatio * availableHeight);

                    g2d.fillRoundRect(thumbBounds.x, newY, thumbBounds.width, FIXED_THUMB_HEIGHT, 10, 10);
                    g2d.dispose();
                }
            });

            verticalScrollBar.setPreferredSize(new Dimension(10, 0));
        }
    }

    public static void addKeyBinding(JButton button, String keyName)
    {
        InputMap inputMap = button.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = button.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(keyName), "click");

        actionMap.put("click", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                button.doClick();
            }
        });
    }
}