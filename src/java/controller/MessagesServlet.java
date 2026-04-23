package com.scms.servlet;

import util.DBConnection;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;

/**
 * MessagesServlet — handles all AJAX calls from messages.jsp
 *
 * Supported "action" values (POST parameter):
 *   getConversations  — returns sidebar contact list for the logged-in user
 *   getMessages       — returns message history for a given conversationId
 *   sendMessage       — sends a message to a conversationId
 *   startChat         — validates email, creates or retrieves conversation
 *   deleteConversation— soft-deletes a conversation for the current user
 *
 * All responses are JSON with { status: "success"|"error", ... }
 */
@WebServlet("/MessagesServlet")
public class MessagesServlet extends HttpServlet {

    // ----------------------------------------------------------------
    // POST entry point — every AJAX call from messages.jsp lands here
    // ----------------------------------------------------------------
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json;charset=UTF-8");
        PrintWriter out = response.getWriter();

        // ── 1. Session guard ────────────────────────────────────────
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("userId") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            out.print(error("Not logged in"));
            return;
        }

        int currentUserId = (int) session.getAttribute("userId");

        // ── 2. Read action ──────────────────────────────────────────
        String action = request.getParameter("action");
        if (action == null || action.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print(error("Invalid action: null"));
            return;
        }

        // ── 3. Dispatch ─────────────────────────────────────────────
        try {
            switch (action.trim()) {
                case "getConversations":
                    out.print(getConversations(currentUserId));
                    break;
                case "getMessages":
                    int convId = intParam(request, "conversationId");
                    out.print(getMessages(currentUserId, convId));
                    break;
                case "sendMessage":
                    int toConvId  = intParam(request, "conversationId");
                    String text   = request.getParameter("message");
                    out.print(sendMessage(currentUserId, toConvId, text));
                    break;
                case "startChat":
                    String email = request.getParameter("email");
                    out.print(startChat(currentUserId, email));
                    break;
                case "deleteConversation":
                    int delConvId = intParam(request, "conversationId");
                    out.print(deleteConversation(currentUserId, delConvId));
                    break;
                default:
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    out.print(error("Unknown action: " + action));
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(error("Server error: " + e.getMessage()));
        }
    }

    // ================================================================
    // ACTION: getConversations
    // Returns conversations where the current user is a participant
    // AND hasn't deleted, OR where new messages arrived after deletion.
    // ================================================================
    private String getConversations(int userId) throws SQLException {
        String sql =
            "SELECT c.CONVERSATION_ID, " +
            "       CASE WHEN c.USER1_ID = ? THEN c.USER2_ID ELSE c.USER1_ID END AS OTHER_USER_ID, " +
            "       u.FIRST_NAME, u.LAST_NAME, u.EMAIL, " +
            "       c.LAST_MESSAGE_AT, " +
            "       (SELECT DBMS_LOB.SUBSTR(m2.MESSAGE_TEXT, 80, 1) " +
            "          FROM MESSAGES m2 " +
            "         WHERE m2.CONVERSATION_ID = c.CONVERSATION_ID " +
            "           AND m2.SENT_AT = c.LAST_MESSAGE_AT " +
            "           AND ROWNUM = 1) AS LAST_MSG " +
            "  FROM CONVERSATIONS c " +
            "  JOIN USERS u ON u.USER_ID = " +
            "       (CASE WHEN c.USER1_ID = ? THEN c.USER2_ID ELSE c.USER1_ID END) " +
            " WHERE (c.USER1_ID = ? OR c.USER2_ID = ?) " +
            "   AND NOT EXISTS ( " +
            "       SELECT 1 FROM CONVERSATION_DELETIONS cd " +
            "        WHERE cd.USER_ID = ? AND cd.CONVERSATION_ID = c.CONVERSATION_ID " +
            "          AND NOT EXISTS ( " +
            "              SELECT 1 FROM MESSAGES m3 " +
            "               WHERE m3.CONVERSATION_ID = c.CONVERSATION_ID " +
            "                 AND m3.SENT_AT > cd.DELETED_AT " +
            "          ) " +
            "   ) " +
            " ORDER BY c.LAST_MESSAGE_AT DESC NULLS LAST";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setInt(4, userId);
            ps.setInt(5, userId);

            ResultSet rs = ps.executeQuery();
            JSONArray list = new JSONArray();
            while (rs.next()) {
                JSONObject obj = new JSONObject();
                obj.put("conversationId",  rs.getInt("CONVERSATION_ID"));
                obj.put("otherUserId",     rs.getInt("OTHER_USER_ID"));
                obj.put("firstName",       rs.getString("FIRST_NAME"));
                obj.put("lastName",        rs.getString("LAST_NAME"));
                obj.put("email",           rs.getString("EMAIL"));
                obj.put("lastMessageAt",   rs.getString("LAST_MESSAGE_AT") != null
                                           ? rs.getString("LAST_MESSAGE_AT") : "");
                obj.put("lastMsg",         rs.getString("LAST_MSG") != null
                                           ? rs.getString("LAST_MSG") : "");
                list.put(obj);
            }
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("conversations", list);
            return result.toString();
        }
    }

    // ================================================================
    // ACTION: getMessages
    // Returns all messages for a conversation (access-checked).
    // ================================================================
    private String getMessages(int userId, int conversationId) throws SQLException {
        // Verify the user belongs to this conversation
        if (!userBelongsToConversation(userId, conversationId)) {
            return error("Access denied");
        }

        // Find the deletion timestamp for this user (if any)
        Timestamp deletedAt = getDeletionTimestamp(userId, conversationId);

        String sql =
            "SELECT m.MESSAGE_ID, m.SENDER_ID, " +
            "       DBMS_LOB.SUBSTR(m.MESSAGE_TEXT, 4000, 1) AS MSG_TEXT, " +
            "       m.SENT_AT, u.FIRST_NAME, u.LAST_NAME " +
            "  FROM MESSAGES m " +
            "  JOIN USERS u ON u.USER_ID = m.SENDER_ID " +
            " WHERE m.CONVERSATION_ID = ? " +
            (deletedAt != null ? "   AND m.SENT_AT > ? " : "") +
            " ORDER BY m.SENT_AT ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, conversationId);
            if (deletedAt != null) {
                ps.setTimestamp(2, deletedAt);
            }
            ResultSet rs = ps.executeQuery();
            JSONArray msgs = new JSONArray();
            while (rs.next()) {
                JSONObject obj = new JSONObject();
                obj.put("messageId",  rs.getInt("MESSAGE_ID"));
                obj.put("senderId",   rs.getInt("SENDER_ID"));
                obj.put("text",       rs.getString("MSG_TEXT"));
                obj.put("sentAt",     rs.getString("SENT_AT"));
                obj.put("firstName",  rs.getString("FIRST_NAME"));
                obj.put("lastName",   rs.getString("LAST_NAME"));
                obj.put("isMine",     rs.getInt("SENDER_ID") == userId);
                msgs.put(obj);
            }
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("messages", msgs);
            return result.toString();
        }
    }

    // ================================================================
    // ACTION: sendMessage
    // Inserts a message and updates LAST_MESSAGE_AT on the conversation.
    // Also removes any deletion record so both users see the conversation.
    // ================================================================
    private String sendMessage(int senderId, int conversationId, String text)
            throws SQLException {
        if (text == null || text.trim().isEmpty()) {
            return error("Message cannot be empty");
        }
        if (!userBelongsToConversation(senderId, conversationId)) {
            return error("Access denied");
        }

        String insertMsg =
            "INSERT INTO MESSAGES (CONCERN_ID, SENDER_ID, MESSAGE_TEXT, CONVERSATION_ID) " +
            "VALUES (NULL, ?, ?, ?)";

        String updateConv =
            "UPDATE CONVERSATIONS SET LAST_MESSAGE_AT = CURRENT_TIMESTAMP " +
            " WHERE CONVERSATION_ID = ?";

        // Remove deletion records for both participants so the chat reappears
        String clearDel =
            "DELETE FROM CONVERSATION_DELETIONS " +
            " WHERE CONVERSATION_ID = ?";

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(insertMsg)) {
                    ps.setInt(1, senderId);
                    ps.setString(2, text.trim());
                    ps.setInt(3, conversationId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(updateConv)) {
                    ps.setInt(1, conversationId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(clearDel)) {
                    ps.setInt(1, conversationId);
                    ps.executeUpdate();
                }
                conn.commit();

                JSONObject result = new JSONObject();
                result.put("status", "success");
                result.put("message", "Message sent");
                return result.toString();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ================================================================
    // ACTION: startChat
    // Validates email → finds user → creates or returns conversation.
    // ================================================================
    private String startChat(int currentUserId, String email) throws SQLException {
        if (email == null || email.trim().isEmpty()) {
            return error("Email is required");
        }

        // Look up the target user by email
        String findUser = "SELECT USER_ID, FIRST_NAME, LAST_NAME FROM USERS " +
                          " WHERE LOWER(EMAIL) = LOWER(?) AND USER_ID != ?";

        int targetUserId;
        String targetFirstName, targetLastName;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(findUser)) {
            ps.setString(1, email.trim());
            ps.setInt(2, currentUserId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                return error("User not found");
            }
            targetUserId    = rs.getInt("USER_ID");
            targetFirstName = rs.getString("FIRST_NAME");
            targetLastName  = rs.getString("LAST_NAME");
        }

        // Enforce canonical ordering: user1 < user2 (avoids duplicate rows)
        int user1 = Math.min(currentUserId, targetUserId);
        int user2 = Math.max(currentUserId, targetUserId);

        // Check if conversation already exists
        String findConv =
            "SELECT CONVERSATION_ID FROM CONVERSATIONS " +
            " WHERE USER1_ID = ? AND USER2_ID = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(findConv)) {
            ps.setInt(1, user1);
            ps.setInt(2, user2);
            ResultSet rs = ps.executeQuery();
            int convId;
            if (rs.next()) {
                convId = rs.getInt("CONVERSATION_ID");
                // If the current user had deleted it, clear that record
                clearDeletion(conn, currentUserId, convId);
            } else {
                // Create new conversation
                String insertConv =
                    "INSERT INTO CONVERSATIONS (USER1_ID, USER2_ID) VALUES (?, ?)";
                try (PreparedStatement ps2 = conn.prepareStatement(insertConv,
                        new String[]{"CONVERSATION_ID"})) {
                    ps2.setInt(1, user1);
                    ps2.setInt(2, user2);
                    ps2.executeUpdate();
                    // The trigger sets CONVERSATION_ID via CONVERSATIONS_SEQ
                    // Retrieve it:
                    ResultSet keys = ps2.getGeneratedKeys();
                    if (keys.next()) {
                        convId = keys.getInt(1);
                    } else {
                        // Fallback: query for it
                        PreparedStatement ps3 = conn.prepareStatement(findConv);
                        ps3.setInt(1, user1);
                        ps3.setInt(2, user2);
                        ResultSet rs3 = ps3.executeQuery();
                        rs3.next();
                        convId = rs3.getInt("CONVERSATION_ID");
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("conversationId",  convId);
            result.put("otherUserId",     targetUserId);
            result.put("firstName",       targetFirstName);
            result.put("lastName",        targetLastName);
            return result.toString();
        }
    }

    // ================================================================
    // ACTION: deleteConversation
    // Soft-deletes the conversation for the current user.
    // A new message from either party will make it reappear.
    // ================================================================
    private String deleteConversation(int userId, int conversationId) throws SQLException {
        if (!userBelongsToConversation(userId, conversationId)) {
            return error("Access denied");
        }

        String upsert =
            "MERGE INTO CONVERSATION_DELETIONS cd " +
            "USING (SELECT ? AS U, ? AS C FROM DUAL) src " +
            "ON (cd.USER_ID = src.U AND cd.CONVERSATION_ID = src.C) " +
            "WHEN NOT MATCHED THEN " +
            "  INSERT (USER_ID, CONVERSATION_ID) VALUES (src.U, src.C)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsert)) {
            ps.setInt(1, userId);
            ps.setInt(2, conversationId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("status", "success");
        result.put("message", "Conversation deleted");
        return result.toString();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private boolean userBelongsToConversation(int userId, int conversationId)
            throws SQLException {
        String sql = "SELECT 1 FROM CONVERSATIONS " +
                     " WHERE CONVERSATION_ID = ? " +
                     "   AND (USER1_ID = ? OR USER2_ID = ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, conversationId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    private Timestamp getDeletionTimestamp(int userId, int conversationId)
            throws SQLException {
        String sql = "SELECT DELETED_AT FROM CONVERSATION_DELETIONS " +
                     " WHERE USER_ID = ? AND CONVERSATION_ID = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, conversationId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getTimestamp("DELETED_AT");
            return null;
        }
    }

    private void clearDeletion(Connection conn, int userId, int conversationId)
            throws SQLException {
        String sql = "DELETE FROM CONVERSATION_DELETIONS " +
                     " WHERE USER_ID = ? AND CONVERSATION_ID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, conversationId);
            ps.executeUpdate();
        }
    }

    private int intParam(HttpServletRequest req, String name) {
        String val = req.getParameter(name);
        if (val == null || val.isEmpty()) return -1;
        try { return Integer.parseInt(val.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private String error(String msg) {
        JSONObject obj = new JSONObject();
        obj.put("status", "error");
        obj.put("message", msg);
        return obj.toString();
    }
}