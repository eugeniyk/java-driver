/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.util.*;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.collect.Iterables.transform;

/**
 *  An immutable representation of a materialized view.
 *  Materialized views are available starting from Cassandra 3.0.
 */
public class MaterializedViewMetadata extends TableOrView {

    private static final Logger logger = LoggerFactory.getLogger(MaterializedViewMetadata.class);

    private final TableMetadata baseTable;

    private final boolean includeAllColumns;

    private MaterializedViewMetadata(
        KeyspaceMetadata keyspace,
        TableMetadata baseTable,
        String name,
        UUID id,
        List<ColumnMetadata> partitionKey,
        List<ColumnMetadata> clusteringColumns,
        Map<String, ColumnMetadata> columns,
        boolean includeAllColumns,
        Options options,
        List<Order> clusteringOrder,
        VersionNumber cassandraVersion) {
        super(keyspace, name, id, partitionKey, clusteringColumns, columns, options, clusteringOrder, cassandraVersion);
        this.baseTable = baseTable;
        this.includeAllColumns = includeAllColumns;
    }

    static MaterializedViewMetadata build(KeyspaceMetadata keyspace, Row row, Map<String, ColumnMetadata.Raw> rawCols, VersionNumber cassandraVersion) {

        String name = row.getString("view_name");
        String tableName = row.getString("base_table_name");
        TableMetadata baseTable = keyspace.getTable(tableName);
        if(baseTable == null) {
            logger.trace(String.format("Cannot find base table %s for materialized view %s.%s: "
                    + "Cluster.getMetadata().getKeyspace(\"%s\").getView(\"%s\") will return null",
                tableName, keyspace.getName(), name, keyspace.getName(), name));
            return null;
        }

        UUID id = row.getUUID("id");
        boolean includeAllColumns = row.getBool("include_all_columns");

        int partitionKeySize = findCollectionSize(rawCols.values(), ColumnMetadata.Raw.Kind.PARTITION_KEY);
        int clusteringSize = findCollectionSize(rawCols.values(), ColumnMetadata.Raw.Kind.CLUSTERING_COLUMN);
        List<ColumnMetadata> partitionKey = nullInitializedList(partitionKeySize);
        List<ColumnMetadata> clusteringColumns = nullInitializedList(clusteringSize);
        List<Order> clusteringOrder = nullInitializedList(clusteringSize);

        // We use a linked hashmap because we will keep this in the order of a 'SELECT * FROM ...'.
        LinkedHashMap<String, ColumnMetadata> columns = new LinkedHashMap<String, ColumnMetadata>();

        Options options = null;
        try {
            options = new Options(row, false, cassandraVersion);
        } catch (RuntimeException e) {
            // See ControlConnection#refreshSchema for why we'd rather not probably this further. Since table options is one thing
            // that tends to change often in Cassandra, it's worth special casing this.
            logger.error(String.format("Error parsing schema options for view %s.%s: "
                    + "Cluster.getMetadata().getKeyspace(\"%s\").getView(\"%s\").getOptions() will return null",
                keyspace.getName(), name, keyspace.getName(), name), e);
        }

        MaterializedViewMetadata view = new MaterializedViewMetadata(
            keyspace, baseTable, name, id, partitionKey, clusteringColumns, columns,
            includeAllColumns, options, clusteringOrder, cassandraVersion);

        // We use this temporary set just so non PK columns are added in lexicographical order, which is the one of a
        // 'SELECT * FROM ...'
        Set<ColumnMetadata> otherColumns = new TreeSet<ColumnMetadata>(columnMetadataComparator);
        for (ColumnMetadata.Raw rawCol : rawCols.values()) {
            ColumnMetadata col = ColumnMetadata.fromRaw(view, rawCol);
            switch (rawCol.kind) {
                case PARTITION_KEY:
                    partitionKey.set(rawCol.position, col);
                    break;
                case CLUSTERING_COLUMN:
                    clusteringColumns.set(rawCol.position, col);
                    clusteringOrder.set(rawCol.position, rawCol.isReversed ? Order.DESC : Order.ASC);
                    break;
                default:
                    otherColumns.add(col);
                    break;
            }
        }
        for (ColumnMetadata c : partitionKey)
            columns.put(c.getName(), c);
        for (ColumnMetadata c : clusteringColumns)
            columns.put(c.getName(), c);
        for (ColumnMetadata c : otherColumns)
            columns.put(c.getName(), c);

        baseTable.add(view);

        return view;

    }

    private static int findCollectionSize(Collection<ColumnMetadata.Raw> cols, ColumnMetadata.Raw.Kind kind) {
        int maxId = -1;
        for (ColumnMetadata.Raw col : cols)
            if (col.kind == kind)
                maxId = Math.max(maxId, col.position);
        return maxId + 1;
    }

    /**
     * Return this materialized view's base table.
     * @return this materialized view's base table.
     */
    public TableMetadata getBaseTable() {
        return baseTable;
    }

    @Override
    protected String asCQLQuery(boolean formatted) {

        String keyspaceName = Metadata.escapeId(keyspace.getName());
        String baseTableName = Metadata.escapeId(baseTable.getName());
        String viewName = Metadata.escapeId(name);
        String whereClause = Joiner.on(" AND ").join(transform(getPartitionKey(), new Function<ColumnMetadata, String>() {
            @Override
            public String apply(ColumnMetadata input) {
                return Metadata.escapeId(input.getName()) + " IS NOT NULL";
            }
        }));


        StringBuilder sb = new StringBuilder();
        sb.append("CREATE MATERIALIZED VIEW ")
            .append(keyspaceName).append('.').append(viewName)
            .append(" AS ");
        newLine(sb, formatted);

        // SELECT
        sb.append("SELECT ");
        if(includeAllColumns) {
            sb.append(" * ");
        } else {
            Iterator<ColumnMetadata> it = columns.values().iterator();
            while(it.hasNext()) {
                ColumnMetadata column = it.next();
                sb.append(spaces(4, formatted)).append(Metadata.escapeId(column.getName()));
                if(it.hasNext()) sb.append(",");
                sb.append(" ");
                newLine(sb, formatted);
            }
        }

        // FROM
        newLine(sb.append("FROM ").append(keyspaceName).append('.').append(baseTableName).append(" "), formatted);

        // WHERE
        newLine(sb.append("WHERE "), formatted);
        Iterator<ColumnMetadata> it = getPrimaryKey().iterator();
        while(it.hasNext()) {
            ColumnMetadata column = it.next();
            sb.append(spaces(4, formatted)).append(Metadata.escapeId(column.getName()));
            sb.append(" IS NOT NULL");
            if(it.hasNext()) sb.append(" AND");
            sb.append(" ");
            newLine(sb, formatted);
        }

        // PK
        sb.append("PRIMARY KEY (");
        if (partitionKey.size() == 1) {
            sb.append(partitionKey.get(0).getName());
        } else {
            sb.append('(');
            boolean first = true;
            for (ColumnMetadata cm : partitionKey) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                sb.append(Metadata.escapeId(cm.getName()));
            }
            sb.append(')');
        }
        for (ColumnMetadata cm : clusteringColumns)
            sb.append(", ").append(Metadata.escapeId(cm.getName()));
        sb.append(')');
        newLine(sb, formatted);

        appendOptions(sb, formatted);
        return sb.toString();

    }

}