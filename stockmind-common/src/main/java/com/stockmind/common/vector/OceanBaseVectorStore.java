package com.stockmind.common.vector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

public class OceanBaseVectorStore {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final DataSource dataSource;
    private final String tableName;
    private final int dimensions;

    public OceanBaseVectorStore(DataSource dataSource, String tableName, int dimensions) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.tableName = validateIdentifier(tableName);
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须大于 0");
        }
        this.dimensions = dimensions;
    }

    /**
     * 创建基础向量表（不存在时）。
     */
    public void createTableIfNotExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "id VARCHAR(128) PRIMARY KEY,"
                + "content TEXT NOT NULL,"
                + "metadata_json JSON NULL,"
                + "embedding VECTOR(" + dimensions + ") NOT NULL,"
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ")";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("创建向量表失败: " + tableName, e);
        }
    }

    /**
     * 存储或更新向量（按 id 覆盖）。
     */
    public void upsert(String id, String content, float[] embedding, String metadataJson) {
        String normalizedId = requireText(id, "id");
        String normalizedContent = requireText(content, "content");
        String vectorLiteral = TextVectorBuilder.toLiteral(validateVector(embedding));

        String sql = "INSERT INTO " + tableName + " (id, content, metadata_json, embedding) "
                + "VALUES (?, ?, ?, CAST(? AS VECTOR(" + dimensions + "))) "
                + "ON DUPLICATE KEY UPDATE content = VALUES(content), "
                + "metadata_json = VALUES(metadata_json), embedding = VALUES(embedding)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, normalizedId);
            preparedStatement.setString(2, normalizedContent);
            preparedStatement.setString(3, metadataJson);
            preparedStatement.setString(4, vectorLiteral);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("向量写入失败: id=" + normalizedId, e);
        }
    }

    /**
     * 按向量相似度检索 TopK。
     */
    public List<VectorSearchResult> search(float[] queryVector, int topK, DistanceMetric metric) {
        float[] normalizedVector = validateVector(queryVector);
        int limit = Math.max(1, topK);
        DistanceMetric distanceMetric = metric == null ? DistanceMetric.COSINE : metric;
        String vectorLiteral = TextVectorBuilder.toLiteral(normalizedVector);

        String distanceSql = distanceMetric.functionName() + "(embedding, CAST(? AS VECTOR(" + dimensions + ")))";
        String sql = "SELECT id, content, metadata_json, " + distanceSql + " AS distance "
                + "FROM " + tableName + " ORDER BY distance ASC LIMIT ?";

        List<VectorSearchResult> results = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, vectorLiteral);
            preparedStatement.setInt(2, limit);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    double distance = resultSet.getDouble("distance");
                    double score = distanceMetric == DistanceMetric.COSINE ? 1.0d - distance : -distance;
                    results.add(new VectorSearchResult(
                            resultSet.getString("id"),
                            resultSet.getString("content"),
                            resultSet.getString("metadata_json"),
                            distance,
                            score));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("向量检索失败", e);
        }
    }

    /**
     * 按主键删除。
     */
    public int deleteById(String id) {
        String normalizedId = requireText(id, "id");
        String sql = "DELETE FROM " + tableName + " WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, normalizedId);
            return preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除向量失败: id=" + normalizedId, e);
        }
    }

    /**
     * 清空全部向量。
     */
    public int deleteAll() {
        String sql = "DELETE FROM " + tableName;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("清空向量表失败: " + tableName, e);
        }
    }

    private float[] validateVector(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalArgumentException("vector 维度不匹配，期望=" + dimensions
                    + "，实际=" + (vector == null ? 0 : vector.length));
        }
        return vector;
    }

    private String requireText(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return text.trim();
    }

    private String validateIdentifier(String identifier) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("非法表名: " + identifier);
        }
        return identifier;
    }
}
