package com.bugbot;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据库操作层
 *
 * ══════════════════════════════════════════════════════════════
 *  修复 SQLITE_BUSY（数据库锁死）问题
 * ══════════════════════════════════════════════════════════════
 *
 * 根本原因：
 *   原版每次操作都调用 DriverManager.getConnection() 创建新连接，
 *   多个连接并发持有事务锁时互相等待，触发 SQLITE_BUSY 死锁。
 *
 * 修复方案（三层保障，缺一不可）：
 *
 *   1. 单共享连接（Single Shared Connection）
 *      整个应用只维护一个 Connection 实例，序列化所有 DB 访问，
 *      从根本上消除多连接竞争。
 *
 *   2. WAL 模式（Write-Ahead Logging）
 *      PRAGMA journal_mode=WAL
 *      WAL 允许读写并发，写操作不阻塞读操作，大幅提升并发性能。
 *
 *   3. busy_timeout
 *      PRAGMA busy_timeout=5000
 *      当锁暂时不可用时最多等待 5 秒再报错，
 *      避免瞬时竞争直接抛出 SQLITE_BUSY。
 *
 *   4. synchronized 方法
 *      所有写操作加 Java 级别的 synchronized，
 *      配合单连接彻底避免并发写冲突。
 */
public class DatabaseManager {

    private final String dbUrl;

    /** 全局唯一共享连接（修复核心：替代每次 getConn()） */
    private Connection sharedConn;

    /** 连接锁，保护 sharedConn 的初始化和重连 */
    private final Object connLock = new Object();

    public DatabaseManager(String dbUrl) {
        this.dbUrl = dbUrl;
        openConnection();
        initDatabase();
    }

    // ── 连接管理 ──────────────────────────────────────────────────────────

    /**
     * 打开共享连接并配置 SQLite 参数
     */
    private void openConnection() {
        synchronized (connLock) {
            try {
                sharedConn = DriverManager.getConnection(dbUrl);
                try (Statement st = sharedConn.createStatement()) {
                    // 修复1：WAL 模式 — 读写并发，写不阻塞读
                    st.execute("PRAGMA journal_mode=WAL");
                    // 修复2：busy_timeout — 锁竞争时最多等待 5 秒
                    st.execute("PRAGMA busy_timeout=5000");
                    // 额外优化：同步模式 NORMAL（WAL 下足够安全且更快）
                    st.execute("PRAGMA synchronous=NORMAL");
                    // 额外优化：缓存大小 8MB
                    st.execute("PRAGMA cache_size=-8000");
                }
                sharedConn.setAutoCommit(true); // 默认自动提交，事务手动控制
            } catch (SQLException e) {
                throw new RuntimeException("打开数据库连接失败", e);
            }
        }
    }

    /**
     * 获取连接，若连接已关闭则自动重连（处理程序长时间运行后连接超时的情况）
     */
    private Connection conn() {
        synchronized (connLock) {
            try {
                if (sharedConn == null || sharedConn.isClosed()) {
                    openConnection();
                }
            } catch (SQLException e) {
                openConnection();
            }
            return sharedConn;
        }
    }

    // ── 初始化 ────────────────────────────────────────────────────────────

    private synchronized void initDatabase() {
        String taskSql =
            "CREATE TABLE IF NOT EXISTS tasks ("
            + "seq                INTEGER NOT NULL, "
            + "id                 TEXT    PRIMARY KEY, "
            + "chat_id            TEXT    NOT NULL, "
            + "description        TEXT    NOT NULL, "
            + "attachment_type    TEXT    NOT NULL DEFAULT '', "
            + "attachment_file_id TEXT    NOT NULL DEFAULT '', "
            + "attachment_name    TEXT    NOT NULL DEFAULT '', "
            + "start_time         INTEGER NOT NULL, "
            + "owner_id           INTEGER NOT NULL DEFAULT 0, "
            + "owner_name         TEXT    NOT NULL DEFAULT '', "
            + "owner_username     TEXT    NOT NULL DEFAULT '', "
            + "status             INTEGER NOT NULL DEFAULT 0, "
            + "claim_time         INTEGER NOT NULL DEFAULT 0, "
            + "done_time          INTEGER NOT NULL DEFAULT 0, "
            + "UNIQUE(chat_id, seq)"
            + ")";

        String activationSql =
            "CREATE TABLE IF NOT EXISTS chat_activation ("
            + "chat_id        TEXT    PRIMARY KEY, "
            + "activated_at   INTEGER NOT NULL, "
            + "activated_by   INTEGER NOT NULL, "
            + "activated_name TEXT    NOT NULL DEFAULT ''"
            + ")";

        try (Statement stmt = conn().createStatement()) {
            stmt.execute(taskSql);
            stmt.execute(activationSql);
            ensureColumn("tasks", "owner_username",
                    "ALTER TABLE tasks ADD COLUMN owner_username TEXT NOT NULL DEFAULT ''");
            ensureColumn("tasks", "attachment_type",
                    "ALTER TABLE tasks ADD COLUMN attachment_type TEXT NOT NULL DEFAULT ''");
            ensureColumn("tasks", "attachment_file_id",
                    "ALTER TABLE tasks ADD COLUMN attachment_file_id TEXT NOT NULL DEFAULT ''");
            ensureColumn("tasks", "attachment_name",
                    "ALTER TABLE tasks ADD COLUMN attachment_name TEXT NOT NULL DEFAULT ''");
        } catch (SQLException e) {
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    // ── 写操作（全部 synchronized）────────────────────────────────────────

    /** 保存普通任务（无附件） */
    public synchronized int saveTask(String id, String chatId, String description) {
        return saveTask(id, chatId, description, "", "", "");
    }

    /** 保存普通任务（含附件） */
    public synchronized int saveTask(String id, String chatId, String description,
                                     String attachmentType, String attachmentFileId,
                                     String attachmentName) {
        String seqSql = "SELECT COALESCE(MAX(seq), 0) + 1 FROM tasks WHERE chat_id = ?";
        String insSql = "INSERT INTO tasks"
                + "(seq,id,chat_id,description,attachment_type,attachment_file_id,attachment_name,start_time) "
                + "VALUES(?,?,?,?,?,?,?,?)";
        try {
            conn().setAutoCommit(false);
            try {
                int seq = nextSeq(seqSql, chatId);
                try (PreparedStatement ps = conn().prepareStatement(insSql)) {
                    ps.setInt(1, seq);
                    ps.setString(2, id);
                    ps.setString(3, chatId);
                    ps.setString(4, description);
                    ps.setString(5, safe(attachmentType));
                    ps.setString(6, safe(attachmentFileId));
                    ps.setString(7, safe(attachmentName));
                    ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                conn().commit();
                return seq;
            } catch (SQLException e) {
                conn().rollback();
                throw e;
            } finally {
                conn().setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存任务失败", e);
        }
    }

    /** 保存派发任务（无附件） */
    public synchronized int saveAssignedTask(String id, String chatId, String description,
                                             String ownerUsername, String ownerName) {
        return saveAssignedTask(id, chatId, description, ownerUsername, ownerName, "", "", "");
    }

    /** 保存派发任务（含附件） */
    public synchronized int saveAssignedTask(String id, String chatId, String description,
                                             String ownerUsername, String ownerName,
                                             String attachmentType, String attachmentFileId,
                                             String attachmentName) {
        String seqSql = "SELECT COALESCE(MAX(seq), 0) + 1 FROM tasks WHERE chat_id = ?";
        String insSql = "INSERT INTO tasks"
                + "(seq,id,chat_id,description,attachment_type,attachment_file_id,attachment_name,"
                + "start_time,owner_name,owner_username,status,claim_time) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,1,?)";
        long now = System.currentTimeMillis();
        try {
            conn().setAutoCommit(false);
            try {
                int seq = nextSeq(seqSql, chatId);
                try (PreparedStatement ps = conn().prepareStatement(insSql)) {
                    ps.setInt(1, seq);
                    ps.setString(2, id);
                    ps.setString(3, chatId);
                    ps.setString(4, description);
                    ps.setString(5, safe(attachmentType));
                    ps.setString(6, safe(attachmentFileId));
                    ps.setString(7, safe(attachmentName));
                    ps.setLong(8, now);
                    ps.setString(9, ownerName == null ? "" : ownerName);
                    ps.setString(10, normalizeUsername(ownerUsername));
                    ps.setLong(11, now);
                    ps.executeUpdate();
                }
                conn().commit();
                return seq;
            } catch (SQLException e) {
                conn().rollback();
                throw e;
            } finally {
                conn().setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("保存派发任务失败", e);
        }
    }

    /** 按序号领取（CAS: status=0 → 1） */
    public synchronized Task claimBySeq(String chatId, int seq, long userId, String userName) {
        String sql = "UPDATE tasks SET owner_id=?,owner_name=?,owner_username='',status=1,claim_time=? "
                   + "WHERE chat_id=? AND seq=? AND status=0";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, userName == null ? "" : userName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, chatId);
            ps.setInt(5, seq);
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("领取任务失败", e);
        }
        return getBySeq(chatId, seq);
    }

    /** 按序号派发已有待领取任务（status=0 → 1） */
    public synchronized Task assignExistingBySeq(String chatId, int seq,
                                                  String ownerUsername, String ownerName) {
        String sql = "UPDATE tasks SET owner_id=0,owner_name=?,owner_username=?,status=1,claim_time=? "
                   + "WHERE chat_id=? AND seq=? AND status=0";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, ownerName == null ? "" : ownerName);
            ps.setString(2, normalizeUsername(ownerUsername));
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, chatId);
            ps.setInt(5, seq);
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("派发任务失败", e);
        }
        return getBySeq(chatId, seq);
    }

    /** 转派处理中任务（status=1，更换负责人） */
    public synchronized Task reassignInProgressBySeq(String chatId, int seq,
                                                      String ownerUsername, String ownerName) {
        String sql = "UPDATE tasks SET owner_id=0,owner_name=?,owner_username=? "
                   + "WHERE chat_id=? AND seq=? AND status=1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, ownerName == null ? "" : ownerName);
            ps.setString(2, normalizeUsername(ownerUsername));
            ps.setString(3, chatId);
            ps.setInt(4, seq);
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("转派任务失败", e);
        }
        return getBySeq(chatId, seq);
    }

    /** 按序号完成（负责人才能操作，status=1 → 2） */
    public synchronized Task doneBySeq(String chatId, int seq, long userId, String actualUsername) {
        String sql = "UPDATE tasks "
                   + "SET status=2,done_time=?,owner_id=CASE WHEN owner_id=0 THEN ? ELSE owner_id END "
                   + "WHERE chat_id=? AND seq=? AND status=1 "
                   + "AND ((owner_id<>0 AND owner_id=?) OR (owner_id=0 AND owner_username=?))";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, userId);
            ps.setString(3, chatId);
            ps.setInt(4, seq);
            ps.setLong(5, userId);
            ps.setString(6, normalizeUsername(actualUsername));
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("完成任务失败", e);
        }
        return getBySeq(chatId, seq);
    }

    /** 按 id+chatId 领取（按钮回调） */
    public synchronized Task claimByIdInChat(String chatId, String id, long userId, String userName) {
        String sql = "UPDATE tasks SET owner_id=?,owner_name=?,owner_username='',status=1,claim_time=? "
                   + "WHERE chat_id=? AND id=? AND status=0";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, userName == null ? "" : userName);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, chatId);
            ps.setString(5, id);
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("领取任务失败", e);
        }
        return getByIdInChat(chatId, id);
    }

    /** 按 id+chatId 完成（按钮回调） */
    public synchronized Task doneByIdInChat(String chatId, String id, long userId, String actualUsername) {
        String sql = "UPDATE tasks "
                   + "SET status=2,done_time=?,owner_id=CASE WHEN owner_id=0 THEN ? ELSE owner_id END "
                   + "WHERE chat_id=? AND id=? AND status=1 "
                   + "AND ((owner_id<>0 AND owner_id=?) OR (owner_id=0 AND owner_username=?))";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, userId);
            ps.setString(3, chatId);
            ps.setString(4, id);
            ps.setLong(5, userId);
            ps.setString(6, normalizeUsername(actualUsername));
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("完成任务失败", e);
        }
        return getByIdInChat(chatId, id);
    }

    // ── 撤销操作 ──────────────────────────────────────────────────────────

    /**
     * 撤销已完成任务（status: 2 → 1）
     * 只有原负责人可以撤销
     */
    public synchronized Task reopenDoneTask(String chatId, int seq, long userId, String actualUsername) {
        String sql = "UPDATE tasks "
                   + "SET status=1,done_time=0,owner_id=CASE WHEN owner_id=0 THEN ? ELSE owner_id END "
                   + "WHERE chat_id=? AND seq=? AND status=2 "
                   + "AND ((owner_id<>0 AND owner_id=?) OR (owner_id=0 AND owner_username=?))";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, chatId);
            ps.setInt(3, seq);
            ps.setLong(4, userId);
            ps.setString(5, normalizeUsername(actualUsername));
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("撤销已完成状态失败", e);
        }
        return getBySeq(chatId, seq);
    }

    /**
     * 撤销领取（status: 1 → 0，清空负责人）
     * 只有当前负责人可以撤销
     */
    public synchronized Task revertInProgressTask(String chatId, int seq, long userId, String actualUsername) {
        String sql = "UPDATE tasks "
                   + "SET status=0,claim_time=0,done_time=0,owner_id=0,owner_name='',owner_username='' "
                   + "WHERE chat_id=? AND seq=? AND status=1 "
                   + "AND ((owner_id<>0 AND owner_id=?) OR (owner_id=0 AND owner_username=?))";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setInt(2, seq);
            ps.setLong(3, userId);
            ps.setString(4, normalizeUsername(actualUsername));
            if (ps.executeUpdate() == 0) return null;
        } catch (SQLException e) {
            throw new RuntimeException("撤销处理中状态失败", e);
        }
        return getBySeq(chatId, seq);
    }

    // ── 读操作（无需 synchronized，WAL 允许并发读）────────────────────────

    public Task getById(String id) {
        String sql = "SELECT * FROM tasks WHERE id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询任务失败", e);
        }
        return null;
    }

    public Task getByIdInChat(String chatId, String id) {
        String sql = "SELECT * FROM tasks WHERE chat_id = ? AND id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询任务失败", e);
        }
        return null;
    }

    public Task getBySeq(String chatId, int seq) {
        String sql = "SELECT * FROM tasks WHERE chat_id = ? AND seq = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setInt(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询任务失败", e);
        }
        return null;
    }

    public List<Task> getActiveTasks(String chatId) {
        return queryList(
            "SELECT * FROM tasks WHERE chat_id = ? AND status < 2 ORDER BY seq ASC", chatId);
    }

    public List<Task> getArchivedTasks(String chatId) {
        return queryList(
            "SELECT * FROM tasks WHERE chat_id = ? AND status = 2 ORDER BY done_time DESC", chatId);
    }

    public List<String> getActiveChatIds() {
        String sql = "SELECT DISTINCT chat_id FROM tasks WHERE status < 2";
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString("chat_id"));
        } catch (SQLException e) {
            throw new RuntimeException("查询活跃群组失败", e);
        }
        return ids;
    }

    public boolean isChatActivated(String chatId) {
        String sql = "SELECT 1 FROM chat_activation WHERE chat_id = ? LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new RuntimeException("查询群激活状态失败", e);
        }
    }

    public synchronized boolean activateChat(String chatId, long userId, String userName) {
        String sql = "INSERT OR IGNORE INTO chat_activation"
                   + "(chat_id,activated_at,activated_by,activated_name) VALUES(?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, userId);
            ps.setString(4, userName == null ? "" : userName);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("激活群组失败", e);
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    private List<Task> queryList(String sql, String chatId) {
        List<Task> list = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询任务列表失败", e);
        }
        return list;
    }

    private Task mapRow(ResultSet rs) throws SQLException {
        return new Task(
                rs.getInt("seq"),
                rs.getString("id"),
                rs.getString("chat_id"),
                rs.getString("description"),
                rs.getString("attachment_type"),
                rs.getString("attachment_file_id"),
                rs.getString("attachment_name"),
                rs.getLong("start_time"),
                rs.getLong("owner_id"),
                rs.getString("owner_name"),
                rs.getString("owner_username"),
                rs.getInt("status"),
                rs.getLong("claim_time"),
                rs.getLong("done_time")
        );
    }

    /**
     * 在事务内计算下一个 seq（必须在 synchronized 块内调用）
     */
    private int nextSeq(String sql, String chatId) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) { return rs.getInt(1); }
        }
    }

    /**
     * 幂等添加列（若列已存在则忽略）
     */
    private void ensureColumn(String table, String column, String alterSql) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("PRAGMA table_info(" + table + ")");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement stmt = conn().createStatement()) {
            stmt.execute(alterSql);
        }
    }

    public static String normalizeUsername(String username) {
        if (username == null) return "";
        String u = username.trim();
        return u.startsWith("@") ? u.substring(1) : u;
    }

    private String safe(String v) { return v == null ? "" : v.trim(); }
}
