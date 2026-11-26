import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Study Circle client (Swing).
 *
 * Threads:
 *   - Swing EDT          : builds/updates the GUI only (never blocks on the network)
 *   - "network-reader"   : connects, then loops on readLine() and hands each line to the EDT
 *   - "sender" executor  : writes outgoing lines so a slow network cannot freeze the window
 */
public class StudyClient extends JFrame {

    // ------------------------------------------------------------ data holders

    /**
     * One row of the Questions list. A row is either a question (replyId == 0) or a reply under it.
     * For a reply row, 'id' is the id of the question it belongs to; 'asker' is then the reply's author.
     */
    private static class QItem {
        final int id;
        final int replyId;
        final int votes;
        final int replies;
        final boolean resolved;
        final boolean mine;
        final String asker;
        final String time;
        final String text;

        QItem(int id, int replyId, int votes, int replies, boolean resolved, boolean mine,
              String asker, String time, String text) {
            this.id = id;
            this.replyId = replyId;
            this.votes = votes;
            this.replies = replies;
            this.resolved = resolved;
            this.mine = mine;
            this.asker = asker;
            this.time = time;
            this.text = text;
        }

        boolean isReply() {
            return replyId > 0;
        }

        @Override
        public String toString() {   // JList shows this; basic HTML gives us bold/strike-through
            String body = esc(text);
            if (isReply()) {
                return "<html><body style='width:200px'>&nbsp;&nbsp;&nbsp;&nbsp;<i>Reply from</i> <b>" + esc(asker)
                        + "</b> <small>(" + esc(time) + ")</small>:<br>&nbsp;&nbsp;&nbsp;&nbsp;" + body + "</body></html>";
            }
            String main = resolved ? "<strike>" + body + "</strike> <b>[resolved]</b>" : "<b>" + body + "</b>";
            return "<html><body style='width:230px'>" + main
                    + "<br><small>votes: " + votes + (mine ? " (you voted)" : "")
                    + " | replies: " + replies
                    + " | asked by " + esc(asker) + "</small></body></html>";
        }
    }

    /**
     * Draws list rows with white text on the deep-rose selection colour (the default made selected rows
     * dark-on-dark). The text is set again after the colours: HTML rows take their colour when the text is set.
     */
    private static class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, false);
            setForeground(isSelected ? Color.WHITE : Theme.INK);
            setBackground(isSelected ? Theme.DEEP_ROSE : Theme.CREAM);
            setBorder(new EmptyBorder(3, 6, 3, 6));
            String text = value == null ? "" : value.toString();
            setText("");
            setText(text);
            return this;
        }
    }

    /** One line of the chat window. A real message has msgId > 0 so it can be edited or deleted. */
    private static class ChatEntry {
        int msgId;                 // 0 = not a chat message (system text) or no longer editable
        boolean mine;
        String time = "";
        String sender = "";
        String text = "";
        boolean edited;
        boolean deleted;
        long receivedAt;           // local clock, used for the 5-minute edit rule (the server checks it too)
        String plain;              // non-null for system/info lines
        int kind;                  // for plain lines: 0 normal, 1 private message, 2 host announcement
        String replySender;        // set when this message is a reply to another message
        String replyPreview;

        static ChatEntry plain(String line) {
            return plain(line, 0);
        }

        static ChatEntry plain(String line, int kind) {
            ChatEntry e = new ChatEntry();
            e.plain = line;
            e.kind = kind;
            return e;
        }

        boolean canModify() {
            return msgId > 0 && mine && !deleted;
        }

        String render() {
            if (plain != null) {
                return plain;
            }
            if (deleted) {
                return "[" + time + "] " + sender + ": (message deleted)";
            }
            String quote = replySender == null ? ""
                    : " (replying to " + replySender + ": \"" + replyPreview + "\")";
            return "[" + time + "] " + sender + quote + ": " + text + (edited ? "  (edited)" : "");
        }
    }

    private static class PollState {
        int id;
        String creator;
        boolean closed;
        String question;
        int myVote;
        final List<String> options = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
    }

    // -------------------------------------------------------------- GUI widgets

    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.DEFAULT_PORT), 5);
    private final JTextField nickField = new JTextField(10);
    private final JButton connectBtn = new JButton("Connect");
    private final JButton renameBtn = new JButton("Change nickname");
    private final JLabel statusLabel = new JLabel("Not connected.");

    private final JTextPane chatArea = new JTextPane();   // styled text: each user's name has their own colour
    private final JTextField inputField = new JTextField();
    private final JButton sendBtn = new JButton("Send");
    private final JButton replyMsgBtn = new JButton("Reply to selected message");
    private final JButton editMsgBtn = new JButton("Edit selected message");
    private final JButton deleteMsgBtn = new JButton("Delete selected message");
    private final JScrollPane chatScroll = new JScrollPane(chatArea);
    private final List<ChatEntry> entries = new ArrayList<>();   // one per line of chatArea (EDT only)
    private int selectedEntry = -1;

    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JButton privateMsgBtn = new JButton("Private message to selected user");
    private final JButton saveChatBtn = new JButton("Save chat as .txt");
    private final Map<String, Integer> nickColors = new HashMap<>();   // nickname -> colour number from the server
    private JPanel barPanel;
    // ---- quiz tab ----
    private final JLabel quizTitleLabel = new JLabel("Waiting for the host's next question ...");
    private final JTextArea quizQuestionArea = new JTextArea(3, 20);
    private final JToggleButton[] quizOptionBtns = new JToggleButton[4];
    private final ButtonGroup quizGroup = new ButtonGroup();
    private final JButton quizSaveBtn = new JButton("Save answer");
    private int quizSelected = -1;       // the choice picked now (can still be changed)
    private final JLabel quizTimeLabel = new JLabel(" ");
    private final JLabel quizResultLabel = new JLabel(" ");
    private final DefaultListModel<String> quizBoardModel = new DefaultListModel<>();
    private final JList<String> quizBoardList = new JList<>(quizBoardModel);
    private javax.swing.Timer quizTimer;
    private long quizEndsAt;
    private int quizId;
    private int quizMyChoice = -1;       // the SAVED answer (locked, cannot be changed any more)
    private boolean quizOpen;
    private String[] quizOptions = new String[0];
    private JTabbedPane rightTabs;
    private volatile boolean waitingForHost;   // removed earlier: waiting for the host to allow us back
    private boolean lastStatusError;
    private boolean timerFired;
    private final JLabel timerLabel = new JLabel("Study timer: not running");
    private javax.swing.Timer countdown;
    private long timerEndsAt;                    // local clock; 0 = no timer
    private volatile boolean kickedByHost;
    private final TitledBorder usersBorder = BorderFactory.createTitledBorder("Online users (0)");

    private final DefaultListModel<QItem> questionModel = new DefaultListModel<>();
    private final JList<QItem> questionList = new JList<>(questionModel);
    private final JTextField questionInput = new JTextField();
    private final JButton askBtn = new JButton("Ask");
    private final JButton upvoteBtn = new JButton("Upvote / un-upvote");
    private final JButton resolveBtn = new JButton("Mark resolved");
    private final JButton unresolveBtn = new JButton("Un-resolve");
    private final JButton replyBtn = new JButton("Reply to selected question");
    private final JButton editQuestionBtn = new JButton("Edit selected");
    private final JButton deleteQuestionBtn = new JButton("Delete selected");

    private final JTextField pollQuestionField = new JTextField();
    private final JTextArea pollOptionsArea = new JTextArea(3, 20);
    private final JButton startPollBtn = new JButton("Start poll");
    private final JPanel pollView = new JPanel();

    // ----------------------------------------------------------- network state

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private ExecutorService sender;
    private volatile boolean connected;
    private volatile boolean intentional;     // true when WE (or an /exit) ended the connection
    private volatile String myNick = "";
    private PollState currentPoll;            // touched only on the EDT

    // ------------------------------------------------------------------- setup

    public StudyClient() {
        super("Study Circle - LAN Chat");
        buildUi();
        setConnectedUi(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                intentional = true;
                closeConnection();
                dispose();
                System.exit(0);
            }
        });
        setSize(1040, 740);
        setMinimumSize(new Dimension(860, 600));
        setLocationRelativeTo(null);
    }

    private void buildUi() {
        // --- connection bar ---
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        barPanel = bar;
        bar.add(barLabel("Server IP:"));
        bar.add(hostField);
        bar.add(barLabel("Port:"));
        bar.add(portField);
        bar.add(barLabel("Nickname:"));
        bar.add(nickField);
        bar.add(connectBtn);
        bar.add(renameBtn);

        // --- chat panel ---
        chatArea.setEditable(false);
        chatArea.setMargin(new Insets(6, 8, 6, 8));
        chatArea.setFont(Theme.CHAT_FONT);
        chatScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        ((DefaultCaret) chatArea.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);   // we scroll ourselves

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(sendBtn, BorderLayout.EAST);

        JPanel chatPanel = new JPanel(new BorderLayout(0, 6));
        chatPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Group chat"), new EmptyBorder(2, 4, 4, 4)));
        JPanel editDeleteRow = new JPanel(new GridLayout(1, 2, 6, 0));
        editDeleteRow.add(editMsgBtn);
        editDeleteRow.add(deleteMsgBtn);
        JPanel msgTools = new JPanel(new GridLayout(3, 1, 0, 6));
        msgTools.add(replyMsgBtn);
        msgTools.add(editDeleteRow);
        msgTools.add(saveChatBtn);
        timerLabel.setFont(timerLabel.getFont().deriveFont(Font.BOLD));
        timerLabel.setBorder(new EmptyBorder(0, 2, 0, 0));
        chatPanel.add(timerLabel, BorderLayout.NORTH);
        JPanel chatSouth = new JPanel(new BorderLayout(0, 6));
        chatSouth.add(inputRow, BorderLayout.NORTH);
        chatSouth.add(msgTools, BorderLayout.SOUTH);
        chatPanel.add(chatScroll, BorderLayout.CENTER);
        chatPanel.add(chatSouth, BorderLayout.SOUTH);

        // --- users panel ---
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new RowRenderer());
        userList.setToolTipText("Double-click your name to change it, or someone else's name to send a private message");
        userList.addListSelectionListener(e -> updatePrivateButton());
        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String v = userList.getSelectedValue();
                if (e.getClickCount() == 2 && v != null) {
                    if (v.endsWith("(you)")) {
                        changeNickname();
                    } else {
                        privateMessageToSelected();
                    }
                }
            }
        });
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.setBorder(usersBorder);
        usersPanel.add(new JScrollPane(userList), BorderLayout.CENTER);
        usersPanel.add(privateMsgBtn, BorderLayout.SOUTH);

        // --- feature 1: question queue tab ---
        questionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        questionList.setCellRenderer(new RowRenderer());
        JPanel askRow = new JPanel(new BorderLayout(6, 0));
        askRow.add(questionInput, BorderLayout.CENTER);
        askRow.add(askBtn, BorderLayout.EAST);
        JPanel voteRow = new JPanel(new GridLayout(1, 2, 6, 0));
        voteRow.add(upvoteBtn);
        voteRow.add(replyBtn);
        JPanel resolveRow = new JPanel(new GridLayout(1, 2, 6, 0));
        resolveRow.add(resolveBtn);
        resolveRow.add(unresolveBtn);
        JPanel editRow = new JPanel(new GridLayout(1, 2, 6, 0));
        editRow.add(editQuestionBtn);
        editRow.add(deleteQuestionBtn);
        JPanel qButtons = new JPanel(new GridLayout(3, 1, 0, 6));
        qButtons.add(voteRow);
        qButtons.add(resolveRow);
        qButtons.add(editRow);
        questionList.addListSelectionListener(e -> updateQuestionButtons());
        JPanel qSouth = new JPanel(new BorderLayout(0, 6));
        qSouth.add(askRow, BorderLayout.NORTH);
        qSouth.add(qButtons, BorderLayout.SOUTH);
        JPanel questionTab = new JPanel(new BorderLayout(0, 6));
        questionTab.setBorder(new EmptyBorder(6, 6, 6, 6));
        questionTab.add(new JLabel("Shared question queue (most upvoted first):"), BorderLayout.NORTH);
        questionTab.add(new JScrollPane(questionList), BorderLayout.CENTER);
        questionTab.add(qSouth, BorderLayout.SOUTH);

        // --- feature 2: live poll tab ---
        pollView.setLayout(new BoxLayout(pollView, BoxLayout.Y_AXIS));
        JPanel pollCreate = new JPanel();
        pollCreate.setLayout(new BoxLayout(pollCreate, BoxLayout.Y_AXIS));
        pollCreate.setBorder(BorderFactory.createTitledBorder("Start a poll"));
        pollCreate.add(leftLabel("Question:"));
        pollCreate.add(pollQuestionField);
        pollCreate.add(Box.createVerticalStrut(4));
        pollCreate.add(leftLabel("Options (one per line, 2 to 5):"));
        pollCreate.add(new JScrollPane(pollOptionsArea));
        pollCreate.add(Box.createVerticalStrut(4));
        startPollBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        pollCreate.add(startPollBtn);
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(pollView, BorderLayout.NORTH);
        JScrollPane pollScroll = new JScrollPane(holder);
        pollScroll.setBorder(BorderFactory.createTitledBorder("Live results"));
        JPanel pollTab = new JPanel(new BorderLayout(0, 6));
        pollTab.setBorder(new EmptyBorder(6, 6, 6, 6));
        pollTab.add(pollCreate, BorderLayout.NORTH);
        pollTab.add(pollScroll, BorderLayout.CENTER);

        // --- feature 3: quiz tab (the host sends a question, everybody answers, leaderboard) ---
        quizTitleLabel.setFont(quizTitleLabel.getFont().deriveFont(Font.BOLD));
        quizTitleLabel.setForeground(Theme.HEADING);
        quizQuestionArea.setEditable(false);
        quizQuestionArea.setLineWrap(true);
        quizQuestionArea.setWrapStyleWord(true);
        quizQuestionArea.setFont(Theme.CHAT_FONT.deriveFont(Font.BOLD));
        quizQuestionArea.setMargin(new Insets(4, 6, 4, 6));
        JPanel optionsPanel = new JPanel(new GridLayout(4, 1, 0, 6));
        for (int i = 0; i < 4; i++) {
            final int index = i;
            quizOptionBtns[i] = new JToggleButton();
            quizOptionBtns[i].setHorizontalAlignment(SwingConstants.LEFT);
            quizOptionBtns[i].addActionListener(e -> selectQuizOption(index));
            quizGroup.add(quizOptionBtns[i]);
            optionsPanel.add(quizOptionBtns[i]);
        }
        quizTimeLabel.setFont(quizTimeLabel.getFont().deriveFont(Font.BOLD));
        quizSaveBtn.addActionListener(e -> saveQuizAnswer());
        JPanel quizInfo = new JPanel(new GridLayout(3, 1, 0, 4));
        quizInfo.add(quizSaveBtn);
        quizInfo.add(quizTimeLabel);
        quizInfo.add(quizResultLabel);
        JPanel quizTop = new JPanel(new BorderLayout(0, 6));
        quizTop.add(quizTitleLabel, BorderLayout.NORTH);
        JPanel quizBody = new JPanel(new BorderLayout(0, 6));
        quizBody.add(new JScrollPane(quizQuestionArea), BorderLayout.NORTH);
        quizBody.add(optionsPanel, BorderLayout.CENTER);
        quizBody.add(quizInfo, BorderLayout.SOUTH);
        quizTop.add(quizBody, BorderLayout.CENTER);
        quizBoardList.setCellRenderer(new RowRenderer());
        JScrollPane quizBoardScroll = new JScrollPane(quizBoardList);
        quizBoardScroll.setBorder(BorderFactory.createTitledBorder("Leaderboard (most correct first)"));
        quizBoardScroll.setPreferredSize(new Dimension(100, 140));
        JPanel quizTab = new JPanel(new BorderLayout(0, 6));
        quizTab.setBorder(new EmptyBorder(6, 6, 6, 6));
        quizTab.add(quizTop, BorderLayout.NORTH);
        quizTab.add(quizBoardScroll, BorderLayout.CENTER);
        resetQuizPanel();

        JTabbedPane tabs = new JTabbedPane();
        rightTabs = tabs;
        tabs.addTab("Questions", questionTab);
        tabs.addTab("Live Poll", pollTab);
        tabs.addTab("Quiz", new JScrollPane(quizTab));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, usersPanel, tabs);
        rightSplit.setResizeWeight(0.18);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chatPanel, rightSplit);
        mainSplit.setResizeWeight(0.58);

        statusLabel.setBorder(new EmptyBorder(6, 10, 6, 10));
        statusLabel.setOpaque(true);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));

        add(bar, BorderLayout.NORTH);
        add(mainSplit, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // --- actions ---
        connectBtn.addActionListener(e -> {
            if (connected || waitingForHost) {
                disconnect();   // while waiting for the host this button says "Cancel"
            } else {
                connect();
            }
        });
        nickField.addActionListener(e -> connectBtn.doClick());
        renameBtn.addActionListener(e -> changeNickname());
        replyMsgBtn.addActionListener(e -> replyToSelectedMessage());
        privateMsgBtn.addActionListener(e -> privateMessageToSelected());
        saveChatBtn.addActionListener(e -> saveChat());
        editMsgBtn.addActionListener(e -> editSelectedMessage());
        deleteMsgBtn.addActionListener(e -> deleteSelectedMessage());
        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                chatClicked(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                chatClicked(e);
            }
        });
        sendBtn.addActionListener(e -> sendChat());
        inputField.addActionListener(e -> sendChat());
        askBtn.addActionListener(e -> askQuestion());
        questionInput.addActionListener(e -> askQuestion());
        upvoteBtn.addActionListener(e -> questionAction(Protocol.C_UPVOTE));
        resolveBtn.addActionListener(e -> questionAction(Protocol.C_RESOLVE));
        unresolveBtn.addActionListener(e -> questionAction(Protocol.C_UNRESOLVE));
        replyBtn.addActionListener(e -> replyToSelected());
        editQuestionBtn.addActionListener(e -> editSelectedQuestion());
        deleteQuestionBtn.addActionListener(e -> deleteSelectedQuestion());
        startPollBtn.addActionListener(e -> startPoll());

        renderPoll();
        applyColors();
    }

    /** Sets every colour that is not handled by the look and feel itself. Called at start and after a mode change. */
    private void applyColors() {
        barPanel.setBackground(Theme.DEEP_ROSE);
        chatArea.setBackground(Theme.CREAM);
        chatArea.setForeground(Theme.INK);
        timerLabel.setForeground(Theme.HEADING);
        statusLabel.setBackground(Theme.DUSTY_PINK);
        statusLabel.setForeground(lastStatusError ? Theme.ERROR : Theme.OK);
    }

    private static JLabel barLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.BAR_TEXT);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JLabel leftLabel(String text) {
        JLabel l = new JLabel(text);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void setConnectedUi(boolean c) {
        hostField.setEnabled(!c);
        portField.setEnabled(!c);
        nickField.setEnabled(!c);
        connectBtn.setEnabled(true);
        connectBtn.setText(c ? "Disconnect" : "Connect");
        renameBtn.setEnabled(c);
        inputField.setEnabled(c);
        sendBtn.setEnabled(c);
        questionInput.setEnabled(c);
        askBtn.setEnabled(c);
        upvoteBtn.setEnabled(c);
        resolveBtn.setEnabled(c);
        unresolveBtn.setEnabled(c);
        pollQuestionField.setEnabled(c);
        pollOptionsArea.setEnabled(c);
        startPollBtn.setEnabled(c);
        updateMsgButtons();
        updateQuestionButtons();
        updatePrivateButton();
    }

    private void setStatus(String text, boolean isError) {
        lastStatusError = isError;
        statusLabel.setForeground(isError ? Theme.ERROR : Theme.OK);
        statusLabel.setText(text);
    }

    // ------------------------------------------------------------ chat window

    private void appendChat(String line) {
        addEntry(ChatEntry.plain(line));
    }

    private void appendChat(String line, int kind) {
        addEntry(ChatEntry.plain(line, kind));
    }

    private void addEntry(ChatEntry e) {
        entries.add(e);
        insertEntry(e);
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = chatScroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    /** Rewrites the whole chat from 'entries' (after an edit, a delete or a light/dark change). */
    private void rebuildChat() {
        final int scrollPos = chatScroll.getVerticalScrollBar().getValue();
        StyledDocument doc = chatArea.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
        }
        for (ChatEntry e : entries) {
            insertEntry(e);
        }
        applySelection();
        SwingUtilities.invokeLater(() -> chatScroll.getVerticalScrollBar().setValue(scrollPos));
    }

    private SimpleAttributeSet style(Color color, boolean bold, boolean italic) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, Theme.CHAT_FONT.getFamily());
        StyleConstants.setFontSize(a, Theme.CHAT_FONT.getSize());
        StyleConstants.setForeground(a, color);
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        return a;
    }

    /** A user keeps the same colour for every message (the server picks the number; unknown names get one from a hash). */
    private Color nameColor(String nick) {
        Integer index = nickColors.get(nick);
        return Theme.userColor(index != null ? index : Math.floorMod(nick.hashCode(), Protocol.NAME_COLORS));
    }

    /** Adds ONE paragraph (= one chat line) at the end of the chat text. */
    private void insertEntry(ChatEntry e) {
        StyledDocument doc = chatArea.getStyledDocument();
        try {
            if (e.plain != null) {
                Color c = e.kind == 1 ? Theme.PRIVATE : (e.kind == 2 ? Theme.ANNOUNCE : Theme.INK);
                doc.insertString(doc.getLength(), e.plain + "\n", style(c, e.kind == 2, e.kind == 1));
                return;
            }
            SimpleAttributeSet normal = style(Theme.INK, false, false);
            SimpleAttributeSet muted = style(Theme.MUTED, false, true);
            doc.insertString(doc.getLength(), "[" + e.time + "] ", style(Theme.MUTED, false, false));
            doc.insertString(doc.getLength(), e.sender, style(nameColor(e.sender), true, false));
            if (e.deleted) {
                doc.insertString(doc.getLength(), ": (message deleted)", muted);
            } else {
                if (e.replySender != null) {
                    doc.insertString(doc.getLength(), " (replying to " + e.replySender + ": \"" + e.replyPreview + "\")", muted);
                }
                doc.insertString(doc.getLength(), ": " + e.text, normal);
                if (e.edited) {
                    doc.insertString(doc.getLength(), "  (edited)", muted);
                }
            }
            doc.insertString(doc.getLength(), "\n", normal);
        } catch (BadLocationException ignored) {
        }
    }

    private ChatEntry findMessage(int msgId) {
        if (msgId <= 0) {
            return null;
        }
        for (ChatEntry e : entries) {
            if (e.msgId == msgId) {
                return e;
            }
        }
        return null;
    }

    private ChatEntry selected() {
        return (selectedEntry >= 0 && selectedEntry < entries.size()) ? entries.get(selectedEntry) : null;
    }

    private void selectEntry(int index) {
        selectedEntry = index;
        applySelection();
    }

    private void applySelection() {
        Highlighter h = chatArea.getHighlighter();
        h.removeAllHighlights();
        ChatEntry e = selected();
        if (e != null && e.plain == null) {
            try {
                Element line = chatArea.getStyledDocument().getDefaultRootElement().getElement(selectedEntry);
                int start = line.getStartOffset();
                int end = Math.max(start, line.getEndOffset() - 1);   // not the line break itself
                h.addHighlight(start, end, new DefaultHighlighter.DefaultHighlightPainter(Theme.DUSTY_PINK));
            } catch (BadLocationException ignored) {
            }
        }
        updateMsgButtons();
    }

    private void updateMsgButtons() {
        ChatEntry e = selected();
        boolean isMessage = connected && e != null && e.msgId > 0 && !e.deleted;   // anyone's message
        boolean ownMessage = connected && e != null && e.canModify();
        replyMsgBtn.setEnabled(isMessage);
        editMsgBtn.setEnabled(ownMessage);
        deleteMsgBtn.setEnabled(ownMessage);
    }

    /** Which chat line is under the mouse? -1 when none. One entry = one paragraph, so the paragraph number is the entry number. */
    private int lineAt(Point p) {
        int offset = chatArea.viewToModel2D(p);
        int line = chatArea.getStyledDocument().getDefaultRootElement().getElementIndex(offset);
        return (line >= 0 && line < entries.size()) ? line : -1;
    }

    private void chatClicked(MouseEvent e) {
        int line = lineAt(e.getPoint());
        if (SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) {
            selectEntry(line);
        }
        if (e.isPopupTrigger() && line >= 0 && connected
                && entries.get(line).msgId > 0 && !entries.get(line).deleted) {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem reply = new JMenuItem("Reply to message");
            reply.addActionListener(a -> replyToSelectedMessage());
            menu.add(reply);
            if (entries.get(line).canModify()) {
                JMenuItem edit = new JMenuItem("Edit message");
                JMenuItem delete = new JMenuItem("Delete message");
                edit.addActionListener(a -> editSelectedMessage());
                delete.addActionListener(a -> deleteSelectedMessage());
                menu.add(edit);
                menu.add(delete);
            }
            menu.show(chatArea, e.getX(), e.getY());
        }
    }

    // ----------------------------------------- nickname / edit / delete actions

    private void changeNickname() {
        if (!connected) {
            return;
        }
        Object input = JOptionPane.showInputDialog(this,
                "New nickname (letters, digits, _ or - only, max " + Protocol.MAX_NICK + "):",
                "Change nickname", JOptionPane.PLAIN_MESSAGE, null, null, myNick);
        if (input == null) {
            return;   // cancelled
        }
        String name = Protocol.clean(input.toString());
        String err = Protocol.checkNick(name);
        if (err == null && name.equals(myNick)) {
            err = "That is already your nickname.";
        }
        if (err != null) {
            setStatus(err, true);
            JOptionPane.showMessageDialog(this, err, "Change nickname", JOptionPane.WARNING_MESSAGE);
            return;
        }
        sendLine(Protocol.line(Protocol.C_CHAT, "/name " + name));   // the server answers with NICK or ERROR
    }

    private void replyToSelectedMessage() {
        ChatEntry e = selected();
        if (!connected || e == null || e.msgId <= 0 || e.deleted) {
            setStatus("Click a message in the chat first.", true);
            return;
        }
        String preview = e.text.length() > 60 ? e.text.substring(0, 57) + "..." : e.text;
        Object input = JOptionPane.showInputDialog(this,
                "Reply to " + e.sender + ": \"" + preview + "\"",
                "Reply to message", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (input == null) {
            return;
        }
        String text = Protocol.clean(input.toString());
        if (text.isEmpty()) {
            setStatus("Type your reply first.", true);
            return;
        }
        if (text.length() > Protocol.MAX_CHAT) {
            setStatus("Message is too long (max " + Protocol.MAX_CHAT + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_REPLY_MSG, String.valueOf(e.msgId), text));
    }

    private void editSelectedMessage() {
        ChatEntry e = selected();
        if (!connected || e == null || !e.canModify()) {
            setStatus("Click one of your own messages first.", true);
            return;
        }
        if (System.currentTimeMillis() - e.receivedAt > Protocol.EDIT_WINDOW_MS) {
            String msg = "You can only edit a message within 5 minutes after sending it.";
            setStatus(msg, true);
            JOptionPane.showMessageDialog(this, msg, "Edit message", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final int id = e.msgId;
        Object input = JOptionPane.showInputDialog(this,
                "Edit your message (allowed for 5 minutes after sending):",
                "Edit message", JOptionPane.PLAIN_MESSAGE, null, null, e.text);
        if (input == null) {
            return;
        }
        String text = Protocol.clean(input.toString());
        if (text.isEmpty()) {
            setStatus("A message cannot be blank. Use Delete to remove it.", true);
            return;
        }
        if (text.length() > Protocol.MAX_CHAT) {
            setStatus("Message is too long (max " + Protocol.MAX_CHAT + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_EDIT, String.valueOf(id), text));
    }

    private void deleteSelectedMessage() {
        ChatEntry e = selected();
        if (!connected || e == null || !e.canModify()) {
            setStatus("Click one of your own messages first.", true);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, "Delete this message for everyone?\n\n" + e.text,
                "Delete message", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            sendLine(Protocol.line(Protocol.C_DELETE, String.valueOf(e.msgId)));
        }
    }

    // ------------------------------------------------------------- connecting

    private static int parsePort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void connect() {
        final String host = hostField.getText().trim();
        final int port = parsePort(portField.getText());
        final String nick = nickField.getText().trim();

        if (host.isEmpty()) {
            setStatus("Enter the server's IP address (or 'localhost').", true);
            return;
        }
        if (port < 0) {
            setStatus("Port must be a number from 1 to 65535.", true);
            return;
        }
        String nickError = Protocol.checkNick(nick);
        if (nickError != null) {
            setStatus(nickError, true);
            return;
        }

        setStatus("Connecting to " + host + ":" + port + " ...", false);
        connectBtn.setEnabled(false);
        hostField.setEnabled(false);
        portField.setEnabled(false);
        nickField.setEnabled(false);
        intentional = false;

        Thread t = new Thread(() -> runConnection(host, port, nick), "network-reader");
        t.setDaemon(true);
        t.start();
    }

    /** Runs on the network thread: connect, join, then read until the connection ends. */
    private void runConnection(final String host, final int port, String nick) {
        Socket s = new Socket();
        String failure;
        try {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (addr.isUnresolved()) {
                throw new UnknownHostException(host);
            }
            s.connect(addr, 5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter w = new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)));
            w.print(Protocol.line(Protocol.C_JOIN, nick) + "\n");
            w.flush();

            s.setSoTimeout(10000);               // do not wait forever for the server's answer
            String reply = r.readLine();
            s.setSoTimeout(0);
            if (reply == null) {
                throw new IOException("The server closed the connection.");
            }
            String[] f = reply.split("\t", -1);
            String deniedMessage = null;
            if (Protocol.S_WAIT.equals(f[0])) {
                // The host removed this nickname earlier: wait, as long as it takes, for the host's decision.
                socket = s;   // so that Cancel can close it
                waitingForHost = true;
                final String msg = f.length > 1 ? f[1] : "Waiting for the host's permission ...";
                SwingUtilities.invokeLater(() -> onWaitingForHost(msg));
                while (true) {
                    reply = r.readLine();
                    if (reply == null) {
                        throw new IOException("The server closed the connection.");
                    }
                    f = reply.split("\t", -1);
                    if (Protocol.S_ALLOWED.equals(f[0])) {
                        w.print(Protocol.line(Protocol.C_JOIN, nick) + "\n");   // allowed: join again
                        w.flush();
                        s.setSoTimeout(10000);
                        reply = r.readLine();
                        s.setSoTimeout(0);
                        if (reply == null) {
                            throw new IOException("The server closed the connection.");
                        }
                        f = reply.split("\t", -1);
                        break;
                    }
                    if (Protocol.S_DENIED.equals(f[0])) {
                        deniedMessage = "The host did not allow you to join again.";
                        break;
                    }
                }
                waitingForHost = false;
            }
            if (deniedMessage == null && Protocol.S_JOINED.equals(f[0]) && f.length > 1) {
                socket = s;
                in = r;
                out = w;
                sender = Executors.newSingleThreadExecutor();
                myNick = f[1];
                connected = true;
                SwingUtilities.invokeLater(() -> onConnected(host, port));
                readLoop();
                return;
            }
            failure = deniedMessage != null ? deniedMessage
                    : (Protocol.S_ERROR.equals(f[0]) && f.length > 1 ? f[1] : "Unexpected reply from the server.");
        } catch (UnknownHostException e) {
            failure = "Unknown server address: " + host;
        } catch (SocketTimeoutException e) {
            failure = "The server at " + host + ":" + port + " did not respond in time.";
        } catch (ConnectException e) {
            failure = "Cannot connect to " + host + ":" + port + ".\nIs the server running, and are the IP address and port correct?";
        } catch (IOException e) {
            failure = "Connection problem: " + e.getMessage();
        }
        try {
            s.close();
        } catch (IOException ignored) {
        }
        waitingForHost = false;
        if (intentional) {   // the user pressed Cancel while waiting for the host
            SwingUtilities.invokeLater(() -> {
                setConnectedUi(false);
                setStatus("Cancelled.", false);
            });
            return;
        }
        final String message = failure;
        SwingUtilities.invokeLater(() -> {
            setConnectedUi(false);
            setStatus(message.replace('\n', ' '), true);
            JOptionPane.showMessageDialog(this, message, "Could not connect", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void readLoop() {
        String reason = null;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                handleServerLine(line);
            }
            if (!intentional) {
                reason = "The connection to the server was lost.";
            }
        } catch (IOException e) {
            if (!intentional) {
                reason = "The connection to the server was lost (" + e.getMessage() + ").";
            }
        } finally {
            closeConnection();
        }
        final String why = reason;
        SwingUtilities.invokeLater(() -> onDisconnected(why));
    }

    private void disconnect() {
        intentional = true;
        connectBtn.setEnabled(false);
        setStatus("Disconnecting ...", false);
        // Close on another thread so the window never waits for the network.
        Thread t = new Thread(this::closeConnection, "disconnect");
        t.setDaemon(true);
        t.start();
        // closing the socket makes readLine() fail, which ends readLoop() -> onDisconnected(null)
    }

    /**
     * Releases the connection. Safe to call more than once and from any thread.
     * IMPORTANT: only the socket is closed. Closing the BufferedReader while the reader thread is
     * blocked inside readLine() would wait for that same thread forever (the old "Disconnect does nothing" bug).
     */
    private void closeConnection() {
        connected = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        ExecutorService ex = sender;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    private void onWaitingForHost(String message) {
        connectBtn.setText("Cancel");
        connectBtn.setEnabled(true);
        setStatus(message, false);
        appendChat("--- " + message + " ---");
    }

    private void onConnected(String host, int port) {
        setConnectedUi(true);
        nickField.setText(myNick);
        setTitle("Study Circle - " + myNick);
        setStatus("Connected to " + host + ":" + port + " as " + myNick, false);
        appendChat("--- Connected to " + host + ":" + port + " ---");
        inputField.requestFocusInWindow();
    }

    private void onDisconnected(String reason) {
        final boolean kicked = kickedByHost;
        kickedByHost = false;
        stopCountdown("Study timer: not running");
        resetQuizPanel();
        quizBoardModel.clear();
        setConnectedUi(false);
        setTitle("Study Circle - LAN Chat");
        userModel.clear();
        usersBorder.setTitle("Online users (0)");
        questionModel.clear();
        currentPoll = null;
        renderPoll();
        repaint();
        for (ChatEntry e : entries) {
            e.msgId = 0;   // old messages can no longer be edited or deleted
        }
        appendChat("--- Disconnected ---");
        selectEntry(-1);
        if (kicked) {
            setStatus("You were removed from the chat by the host.", true);
            JOptionPane.showMessageDialog(this, "The host removed you from the chat.\n"
                    + "To join again, the host must give you permission.", "Removed", JOptionPane.WARNING_MESSAGE);
        } else if (reason == null) {
            setStatus("Disconnected.", false);
        } else {
            setStatus(reason, true);
            JOptionPane.showMessageDialog(this, reason, "Connection lost", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------- receiving (EDT-safe)

    private void handleServerLine(String line) {
        final String[] f = line.split("\t", -1);
        try {
            switch (f[0]) {
                case Protocol.S_MSG:
                    ui(() -> addMessage(f));
                    break;
                case Protocol.S_EDITED:
                    ui(() -> {
                        ChatEntry e = findMessage(Integer.parseInt(f[1]));
                        if (e != null) {
                            e.text = f[2];
                            e.edited = true;
                            rebuildChat();
                        }
                    });
                    break;
                case Protocol.S_DELETED:
                    ui(() -> {
                        ChatEntry e = findMessage(Integer.parseInt(f[1]));
                        if (e != null) {
                            e.deleted = true;
                            e.text = "";
                            rebuildChat();
                        }
                    });
                    break;
                case Protocol.S_SYS:
                    ui(() -> appendChat("[" + f[1] + "]  * " + f[2]));
                    break;
                case Protocol.S_ERROR:
                    ui(() -> {
                        setStatus(f[1], true);
                        appendChat("  ! " + f[1]);
                    });
                    break;
                case Protocol.S_USERS:
                    ui(() -> updateUsers(f));
                    break;
                case Protocol.S_NICK:
                    myNick = f[1];
                    ui(() -> {
                        nickField.setText(f[1]);
                        setTitle("Study Circle - " + f[1]);
                        setStatus("You are now known as " + f[1], false);
                        updateQuestionButtons();
                    });
                    break;
                case Protocol.S_QUEUE:
                    ui(() -> updateQueue(f));
                    break;
                case Protocol.S_POLL:
                    ui(() -> updatePoll(f));
                    break;
                case Protocol.S_COLORS:
                    ui(() -> {
                        for (int i = 1; i + 1 < f.length; i += 2) {
                            nickColors.put(f[i], Integer.parseInt(f[i + 1]));   // old nicknames stay, so old messages keep their colour
                        }
                    });
                    break;
                case Protocol.S_QUIZ:
                    ui(() -> onQuizStart(f));
                    break;
                case Protocol.S_QUIZ_ACK:
                    ui(() -> {
                        if (Integer.parseInt(f[1]) == quizId) {
                            quizResultLabel.setText("Your answer is locked in. Waiting for the result ...");
                        }
                    });
                    break;
                case Protocol.S_QUIZ_RESULT:
                    ui(() -> onQuizResult(f));
                    break;
                case Protocol.S_QUIZ_BOARD:
                    ui(() -> updateQuizBoard(f));
                    break;
                case Protocol.S_PM:
                    ui(() -> appendChat("[" + f[1] + "] (private) " + (f[2].equals(myNick) ? "You" : f[2])
                            + " to " + (f[3].equals(myNick) ? "you" : f[3]) + ": " + f[4], 1));
                    break;
                case Protocol.S_ANNOUNCE:
                    ui(() -> {
                        appendChat("[" + f[1] + "] *** ANNOUNCEMENT FROM THE HOST: " + f[2] + " ***", 2);
                        setStatus("Announcement: " + f[2], false);
                        Toolkit.getDefaultToolkit().beep();
                    });
                    break;
                case Protocol.S_TIMER:
                    ui(() -> {
                        if ("running".equals(f[2])) {
                            startCountdown(Integer.parseInt(f[1]));
                        } else if ("finished".equals(f[2])) {
                            timerFinished();
                        } else {
                            stopCountdown("Study timer: not running");
                        }
                    });
                    break;
                case Protocol.S_KICKED:
                    kickedByHost = true;
                    break;
                case Protocol.S_BYE:
                    intentional = true;   // server is closing because we typed /exit
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            // malformed line from the server: ignore it rather than crash the reader thread
        }
    }

    // ------------------------------------------------ quiz

    private static char letter(int i) {
        return (char) ('A' + i);
    }

    private String quizOptionText(int i) {
        return letter(i) + ".  " + quizOptions[i];
    }

    private void resetQuizPanel() {
        quizOpen = false;
        quizMyChoice = -1;
        quizSelected = -1;
        quizId = 0;
        quizOptions = new String[0];
        if (quizTimer != null) {
            quizTimer.stop();
        }
        quizGroup.clearSelection();
        quizTitleLabel.setText("Waiting for the host's next question ...");
        quizQuestionArea.setText("");
        quizTimeLabel.setText(" ");
        quizResultLabel.setText(" ");
        quizSaveBtn.setEnabled(false);
        for (JToggleButton b : quizOptionBtns) {
            b.setVisible(false);
            b.setEnabled(false);
        }
    }

    /** Shows each choice's text; the picked one is marked "(selected)", the saved one "(your answer)". */
    private void refreshQuizButtons() {
        for (int i = 0; i < quizOptionBtns.length && i < quizOptions.length; i++) {
            String mark = "";
            if (quizMyChoice >= 0) {
                mark = (i == quizMyChoice) ? "      (your answer)" : "";
            } else if (i == quizSelected) {
                mark = "      (selected)";
            }
            quizOptionBtns[i].setText(quizOptionText(i) + mark);
        }
    }

    /** QUIZ id secondsLeft question n option... : the host started a question. */
    private void onQuizStart(String[] f) {
        quizId = Integer.parseInt(f[1]);
        int seconds = Integer.parseInt(f[2]);
        int n = Integer.parseInt(f[4]);
        quizOptions = new String[n];
        for (int i = 0; i < n; i++) {
            quizOptions[i] = f[5 + i];
        }
        quizMyChoice = -1;
        quizSelected = -1;
        quizGroup.clearSelection();
        quizOpen = true;
        quizTitleLabel.setText("Question " + quizId);
        quizQuestionArea.setText(f[3]);
        quizQuestionArea.setCaretPosition(0);
        quizResultLabel.setText("Pick an answer (you can change it), then click \"Save answer\" to lock it in.");
        for (int i = 0; i < quizOptionBtns.length; i++) {
            boolean used = i < n;
            quizOptionBtns[i].setVisible(used);
            if (used) {
                quizOptionBtns[i].setToolTipText(quizOptions[i]);
                quizOptionBtns[i].setEnabled(connected);
            }
        }
        refreshQuizButtons();
        quizSaveBtn.setEnabled(false);   // until something is picked
        startQuizCountdown(seconds);
        if (rightTabs != null) {
            rightTabs.setSelectedIndex(rightTabs.indexOfTab("Quiz"));   // bring the question to the front
        }
        Toolkit.getDefaultToolkit().beep();
    }

    /** Clicking a choice only PICKS it. You can pick another one as often as you like until you save. */
    private void selectQuizOption(int index) {
        if (!connected || !quizOpen || quizMyChoice >= 0 || index >= quizOptions.length) {
            return;
        }
        quizSelected = index;
        refreshQuizButtons();
        quizSaveBtn.setEnabled(true);
        quizResultLabel.setText("You picked " + letter(index) + ". Not saved yet: click \"Save answer\" to lock it in.");
    }

    /** Save answer: sends the picked choice to the server. After this it cannot be changed. */
    private void saveQuizAnswer() {
        if (!connected || !quizOpen || quizMyChoice >= 0 || quizSelected < 0) {
            return;
        }
        quizMyChoice = quizSelected;
        for (JToggleButton b : quizOptionBtns) {
            b.setEnabled(false);   // locked
        }
        quizSaveBtn.setEnabled(false);
        refreshQuizButtons();
        quizResultLabel.setText("Answer saved ...");
        sendLine(Protocol.line(Protocol.C_QUIZ_ANSWER, String.valueOf(quizId), String.valueOf(quizMyChoice)));
    }

    private void startQuizCountdown(int seconds) {
        quizEndsAt = System.currentTimeMillis() + seconds * 1000L;
        if (quizTimer == null) {
            quizTimer = new javax.swing.Timer(250, e -> tickQuiz());
        }
        quizTimer.start();
        tickQuiz();
    }

    private void tickQuiz() {
        long left = (quizEndsAt - System.currentTimeMillis() + 999) / 1000;
        if (left <= 0) {
            quizTimer.stop();
            quizTimeLabel.setText("Time is up!");
            for (JToggleButton b : quizOptionBtns) {
                b.setEnabled(false);
            }
            quizSaveBtn.setEnabled(false);
            return;
        }
        boolean unsaved = quizMyChoice < 0 && quizSelected >= 0;
        quizTimeLabel.setText("Time left: " + Protocol.formatDuration(left)
                + (left <= 5 && unsaved ? "   -   click Save answer!" : ""));
    }

    /** QZRESULT id correctIndex n count... : the question is over. */
    private void onQuizResult(String[] f) {
        if (Integer.parseInt(f[1]) != quizId) {
            return;   // not the question this screen is showing
        }
        int correct = Integer.parseInt(f[2]);
        int n = Integer.parseInt(f[3]);
        quizOpen = false;
        if (quizTimer != null) {
            quizTimer.stop();
        }
        quizSaveBtn.setEnabled(false);
        for (int i = 0; i < n && i < quizOptionBtns.length && i < quizOptions.length; i++) {
            quizOptionBtns[i].setText(quizOptionText(i) + "      -  " + f[4 + i] + " chose this"
                    + (i == correct ? "      [CORRECT]" : "") + (i == quizMyChoice ? "   (your answer)" : ""));
            quizOptionBtns[i].setEnabled(false);
        }
        quizTimeLabel.setText("Question closed.");
        if (quizMyChoice < 0 && quizSelected >= 0) {
            quizResultLabel.setText("Time ran out before you saved your answer (you had picked " + letter(quizSelected)
                    + "). The correct answer is " + letter(correct) + ".");
        } else if (quizMyChoice < 0) {
            quizResultLabel.setText("You did not answer. The correct answer is " + letter(correct) + ".");
        } else if (quizMyChoice == correct) {
            quizResultLabel.setText("Correct! +1 point");
        } else {
            quizResultLabel.setText("Not this time. The correct answer is " + letter(correct) + ".");
        }
        Toolkit.getDefaultToolkit().beep();
    }

    /** QZBOARD n (nick correct answered)* : the leaderboard. */
    private void updateQuizBoard(String[] f) {
        quizBoardModel.clear();
        int n = Integer.parseInt(f[1]);
        for (int i = 0; i < n; i++) {
            String name = f[2 + 3 * i];
            quizBoardModel.addElement((i + 1) + ".  " + name + (name.equals(myNick) ? " (you)" : "")
                    + "   -   " + f[3 + 3 * i] + " correct (" + f[4 + 3 * i] + " answered)");
        }
    }

    // ------------------------------------------------ private message / save chat / timer

    private void updatePrivateButton() {
        String v = userList.getSelectedValue();
        privateMsgBtn.setEnabled(connected && v != null && !v.endsWith("(you)"));
    }

    private void privateMessageToSelected() {
        String name = userList.getSelectedValue();
        if (!connected || name == null || name.endsWith("(you)")) {
            setStatus("Click another user in the Online users list first.", true);
            return;
        }
        Object input = JOptionPane.showInputDialog(this,
                "Private message to " + name + " (only you and " + name + " will see it):",
                "Private message", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (input == null) {
            return;
        }
        String text = Protocol.clean(input.toString());
        if (text.isEmpty()) {
            setStatus("Type your message first.", true);
            return;
        }
        if (text.length() > Protocol.MAX_CHAT) {
            setStatus("Message is too long (max " + Protocol.MAX_CHAT + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_PM, name, text));
    }

    private void saveChat() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save chat");
        chooser.setSelectedFile(new File("StudyCircle-chat-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".txt")) {
            file = new File(file.getParentFile(), file.getName() + ".txt");
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, file.getName() + " already exists. Replace it?",
                "Save chat", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            w.write("Study Circle chat - saved " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            w.newLine();
            w.newLine();
            for (ChatEntry e : entries) {
                w.write(e.render());
                w.newLine();
            }
            setStatus("Chat saved to " + file.getAbsolutePath(), false);
        } catch (IOException ex) {
            setStatus("Could not save the chat: " + ex.getMessage(), true);
            JOptionPane.showMessageDialog(this, "Could not save the chat:\n" + ex.getMessage(), "Save chat", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** The server sends the seconds left; we count down on this computer (no clock sync needed). */
    private void startCountdown(int seconds) {
        timerEndsAt = System.currentTimeMillis() + seconds * 1000L;
        timerFired = false;
        if (countdown == null) {
            countdown = new javax.swing.Timer(500, e -> tickCountdown());
        }
        countdown.start();
        tickCountdown();
    }

    private void tickCountdown() {
        long left = (timerEndsAt - System.currentTimeMillis() + 999) / 1000;
        if (left <= 0) {
            timerFinished();
            return;
        }
        timerLabel.setText("Study timer: " + Protocol.formatDuration(left) + " left");
    }

    /** Time is up: sound + a pop-up message (once, even though both the local clock and the server report it). */
    private void timerFinished() {
        if (timerFired) {
            return;
        }
        timerFired = true;
        stopCountdown("Study timer: finished! Take a break.");
        Toolkit.getDefaultToolkit().beep();
        toFront();
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                "Your study timer is finished.\nTime for a break!", "Study timer", JOptionPane.INFORMATION_MESSAGE));
    }

    private void stopCountdown(String text) {
        timerEndsAt = 0;
        if (countdown != null) {
            countdown.stop();
        }
        timerLabel.setText(text);
    }

    private void addMessage(String[] f) {
        ChatEntry e = new ChatEntry();
        e.time = f[1];
        e.sender = f[2];
        e.text = f[3];
        e.msgId = f.length > 4 ? Integer.parseInt(f[4]) : 0;
        e.mine = f[2].equals(myNick);
        if (f.length > 6 && !f[5].isEmpty()) {
            e.replySender = f[5];
            e.replyPreview = f[6];
        }
        e.receivedAt = System.currentTimeMillis();
        addEntry(e);
    }

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> {
            try {
                r.run();
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void updateUsers(String[] f) {
        userModel.clear();
        for (int i = 1; i < f.length; i++) {
            userModel.addElement(f[i].equals(myNick) ? f[i] + "  (you)" : f[i]);
        }
        usersBorder.setTitle("Online users (" + (f.length - 1) + ")");
        updatePrivateButton();
        repaint();
    }

    private void updateQueue(String[] f) {
        int selectedId = -1;
        int selectedReply = 0;
        QItem sel = questionList.getSelectedValue();
        if (sel != null) {
            selectedId = sel.id;
            selectedReply = sel.replyId;
        }
        questionModel.clear();
        int n = Integer.parseInt(f[1]);
        int index = 2;
        for (int i = 0; i < n; i++) {
            int qid = Integer.parseInt(f[index]);
            int votes = Integer.parseInt(f[index + 1]);
            boolean resolved = "1".equals(f[index + 2]);
            boolean voted = "1".equals(f[index + 3]);
            String asker = f[index + 4];
            String text = f[index + 5];
            int replyCount = Integer.parseInt(f[index + 6]);
            index += 7;
            questionModel.addElement(new QItem(qid, 0, votes, replyCount, resolved, voted, asker, "", text));
            if (qid == selectedId && selectedReply == 0) {
                questionList.setSelectedIndex(questionModel.size() - 1);
            }
            for (int r = 0; r < replyCount; r++, index += 4) {
                int rid = Integer.parseInt(f[index]);
                questionModel.addElement(new QItem(qid, rid, votes, 0, resolved, voted, f[index + 1], f[index + 2], f[index + 3]));
                if (qid == selectedId && rid == selectedReply) {
                    questionList.setSelectedIndex(questionModel.size() - 1);
                }
            }
        }
        updateQuestionButtons();
    }

    private void updatePoll(String[] f) {
        if ("NONE".equals(f[1])) {
            currentPoll = null;
        } else {
            PollState p = new PollState();
            p.id = Integer.parseInt(f[1]);
            p.creator = f[2];
            p.closed = "1".equals(f[3]);
            p.question = f[4];
            p.myVote = Integer.parseInt(f[5]);
            int n = Integer.parseInt(f[6]);
            for (int i = 0; i < n; i++) {
                p.options.add(f[7 + 2 * i]);
                p.counts.add(Integer.parseInt(f[8 + 2 * i]));
            }
            currentPoll = p;
        }
        renderPoll();
    }

    private void renderPoll() {
        pollView.removeAll();
        PollState p = currentPoll;
        if (p == null) {
            JLabel none = new JLabel("No poll yet. Start one above!");
            none.setBorder(new EmptyBorder(6, 6, 6, 6));
            pollView.add(none);
        } else {
            int total = 0;
            for (int c : p.counts) {
                total += c;
            }
            JLabel q = new JLabel("<html><body style='width:240px'><b>" + esc(p.question) + "</b></body></html>");
            q.setForeground(Theme.WINE);
            q.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel meta = new JLabel("Started by " + p.creator + "  |  " + (p.closed ? "CLOSED" : "OPEN")
                    + "  |  " + total + " vote" + (total == 1 ? "" : "s"));
            meta.setAlignmentX(Component.LEFT_ALIGNMENT);
            pollView.add(q);
            pollView.add(meta);
            pollView.add(Box.createVerticalStrut(6));
            for (int i = 0; i < p.options.size(); i++) {
                final int option = i;
                final int pollId = p.id;
                int count = p.counts.get(i);
                int pct = total == 0 ? 0 : Math.round(100f * count / total);
                JProgressBar bar = new JProgressBar(0, Math.max(total, 1));
                bar.setValue(count);
                bar.setStringPainted(true);
                bar.setString(p.options.get(i) + "  -  " + count + " (" + pct + "%)");
                JButton vote = new JButton(i == p.myVote ? "Voted" : "Vote");
                vote.setEnabled(!p.closed && connected);
                vote.addActionListener(e -> sendLine(Protocol.line(Protocol.C_VOTE, String.valueOf(pollId), String.valueOf(option))));
                JPanel row = new JPanel(new BorderLayout(6, 0));
                row.setBorder(new EmptyBorder(2, 0, 2, 0));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.add(bar, BorderLayout.CENTER);
                row.add(vote, BorderLayout.EAST);
                pollView.add(row);
            }
            if (!p.closed && p.creator.equals(myNick)) {
                JButton close = new JButton("Close poll");
                final int pollId = p.id;
                close.setAlignmentX(Component.LEFT_ALIGNMENT);
                close.addActionListener(e -> sendLine(Protocol.line(Protocol.C_POLL_END, String.valueOf(pollId))));
                pollView.add(Box.createVerticalStrut(6));
                pollView.add(close);
            }
        }
        pollView.revalidate();
        pollView.repaint();
    }

    // --------------------------------------------------------------- sending

    private void sendLine(final String line) {
        final ExecutorService ex = sender;
        if (!connected || ex == null) {
            setStatus("You are not connected to a server.", true);
            return;
        }
        try {
            ex.execute(() -> {
                PrintWriter w = out;
                if (w == null) {
                    return;
                }
                w.print(line);
                w.print('\n');
                w.flush();
                if (w.checkError()) {
                    closeConnection();   // reader thread will report "connection lost"
                }
            });
        } catch (RejectedExecutionException e) {
            setStatus("You are not connected to a server.", true);
        }
    }

    private void sendChat() {
        String text = Protocol.clean(inputField.getText());
        if (text.isEmpty()) {
            setStatus("Cannot send a blank message.", true);
            return;
        }
        if (text.length() > Protocol.MAX_CHAT) {
            setStatus("Message is too long (max " + Protocol.MAX_CHAT + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_CHAT, text));
        inputField.setText("");
        setStatus("Connected as " + myNick, false);
    }

    private void askQuestion() {
        String text = Protocol.clean(questionInput.getText());
        if (text.isEmpty()) {
            setStatus("Type a question first.", true);
            return;
        }
        if (text.length() > Protocol.MAX_QUESTION) {
            setStatus("Question is too long (max " + Protocol.MAX_QUESTION + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_ASK, text));
        questionInput.setText("");
    }

    private void questionAction(String command) {
        QItem sel = questionList.getSelectedValue();
        if (sel == null) {
            setStatus("Select a question in the list first.", true);
            return;
        }
        sendLine(Protocol.line(command, String.valueOf(sel.id)));   // for a reply row, 'id' is its question
    }

    /** Reply is for everyone; Edit/Delete only for the owner of the selected question or reply (server checks too). */
    private void updateQuestionButtons() {
        QItem q = questionList.getSelectedValue();
        replyBtn.setEnabled(connected && q != null);
        resolveBtn.setEnabled(connected && q != null && !q.resolved);     // open question -> can be resolved
        unresolveBtn.setEnabled(connected && q != null && q.resolved);    // resolved question -> can be reopened
        boolean mine = connected && q != null && q.asker.equals(myNick);
        editQuestionBtn.setEnabled(mine);
        deleteQuestionBtn.setEnabled(mine);
    }

    private void replyToSelected() {
        QItem q = questionList.getSelectedValue();
        if (!connected || q == null) {
            setStatus("Select a question in the list first.", true);
            return;
        }
        Object input = JOptionPane.showInputDialog(this, "Your reply:", "Reply to question",
                JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (input == null) {
            return;
        }
        String text = Protocol.clean(input.toString());
        if (text.isEmpty()) {
            setStatus("Type your reply first.", true);
            return;
        }
        if (text.length() > Protocol.MAX_REPLY) {
            setStatus("Reply is too long (max " + Protocol.MAX_REPLY + " characters).", true);
            return;
        }
        sendLine(Protocol.line(Protocol.C_Q_REPLY, String.valueOf(q.id), text));
    }

    private void editSelectedQuestion() {
        QItem q = questionList.getSelectedValue();
        if (!connected || q == null || !q.asker.equals(myNick)) {
            setStatus("Select one of your own questions or replies first.", true);
            return;
        }
        String what = q.isReply() ? "reply" : "question";
        Object input = JOptionPane.showInputDialog(this, "Edit your " + what + ":", "Edit " + what,
                JOptionPane.PLAIN_MESSAGE, null, null, q.text);
        if (input == null) {
            return;
        }
        String text = Protocol.clean(input.toString());
        int max = q.isReply() ? Protocol.MAX_REPLY : Protocol.MAX_QUESTION;
        if (text.isEmpty()) {
            setStatus("It cannot be blank. Use Delete selected to remove it.", true);
            return;
        }
        if (text.length() > max) {
            setStatus("Too long (max " + max + " characters).", true);
            return;
        }
        if (q.isReply()) {
            sendLine(Protocol.line(Protocol.C_R_EDIT, String.valueOf(q.replyId), text));
        } else {
            sendLine(Protocol.line(Protocol.C_Q_EDIT, String.valueOf(q.id), text));
        }
    }

    private void deleteSelectedQuestion() {
        QItem q = questionList.getSelectedValue();
        if (!connected || q == null || !q.asker.equals(myNick)) {
            setStatus("Select one of your own questions or replies first.", true);
            return;
        }
        String what = q.isReply() ? "reply" : "question (its replies are deleted too)";
        int answer = JOptionPane.showConfirmDialog(this, "Delete this " + what + " for everyone?\n\n" + q.text,
                "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            if (q.isReply()) {
                sendLine(Protocol.line(Protocol.C_R_DELETE, String.valueOf(q.replyId)));
            } else {
                sendLine(Protocol.line(Protocol.C_Q_DELETE, String.valueOf(q.id)));
            }
        }
    }

    private void startPoll() {
        String question = Protocol.clean(pollQuestionField.getText());
        List<String> options = new ArrayList<>();
        for (String o : pollOptionsArea.getText().split("\n")) {
            String clean = Protocol.clean(o);
            if (!clean.isEmpty()) {
                options.add(clean);
            }
        }
        if (question.isEmpty()) {
            setStatus("Type the poll question first.", true);
            return;
        }
        if (question.length() > Protocol.MAX_POLL_QUESTION) {
            setStatus("Poll question is too long (max " + Protocol.MAX_POLL_QUESTION + " characters).", true);
            return;
        }
        if (options.size() < Protocol.MIN_OPTIONS || options.size() > Protocol.MAX_OPTIONS) {
            setStatus("Enter " + Protocol.MIN_OPTIONS + " to " + Protocol.MAX_OPTIONS + " options, one per line.", true);
            return;
        }
        for (String o : options) {
            if (o.length() > Protocol.MAX_POLL_OPTION) {
                setStatus("Each option may have at most " + Protocol.MAX_POLL_OPTION + " characters.", true);
                return;
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add(Protocol.C_POLL_START);
        parts.add(question);
        parts.addAll(options);
        sendLine(String.join(Protocol.SEP, parts));
        pollQuestionField.setText("");
        pollOptionsArea.setText("");
    }

    // ------------------------------------------------------------- utilities

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) {
        Theme.apply();
        SwingUtilities.invokeLater(() -> new StudyClient().setVisible(true));
    }
}
