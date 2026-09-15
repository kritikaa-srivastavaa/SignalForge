package com.kritika.signalforge.common;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
class DatabaseIndexIntegrationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@ParameterizedTest(name = "{0}")
	@MethodSource("expectedIndexes")
	void indexHasExpectedTableColumnsAndOrdering(String name, String table, List<String> columns) {
		// Read catalog attributes rather than comparing formatted CREATE INDEX text.
		List<IndexColumn> actual = jdbcTemplate.query("""
				SELECT tbl.relname AS table_name, attr.attname AS column_name,
				       (idx.indoption[position.n] & 1) = 1 AS descending,
				       idx.indisvalid, idx.indisready, am.amname
				FROM pg_index idx
				JOIN pg_class index_table ON index_table.oid = idx.indexrelid
				JOIN pg_class tbl ON tbl.oid = idx.indrelid
				JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
				JOIN pg_am am ON am.oid = index_table.relam
				CROSS JOIN LATERAL generate_series(0, idx.indnatts - 1) AS position(n)
				JOIN pg_attribute attr ON attr.attrelid = tbl.oid AND attr.attnum = idx.indkey[position.n]
				WHERE ns.nspname = current_schema() AND index_table.relname = ?
				ORDER BY position.n
				""", (rs, row) -> new IndexColumn(rs.getString("table_name"),
					rs.getString("column_name") + (rs.getBoolean("descending") ? " DESC" : " ASC"),
					rs.getBoolean("indisvalid"), rs.getBoolean("indisready"), rs.getString("amname")), name);

		assertThat(actual).hasSize(columns.size());
		assertThat(actual).extracting(IndexColumn::definition).containsExactlyElementsOf(columns);
		assertThat(actual).allSatisfy(column -> {
			assertThat(column.table()).isEqualTo(table);
			assertThat(column.valid()).isTrue();
			assertThat(column.ready()).isTrue();
			assertThat(column.method()).isEqualTo("btree");
		});
	}

	private record IndexColumn(String table, String definition, boolean valid, boolean ready, String method) {
	}

	static Stream<Arguments> expectedIndexes() {
		return Stream.of(
				Arguments.of("idx_events_timestamp_id", "events", List.of("timestamp DESC", "id DESC")),
				Arguments.of("idx_events_service_timestamp_id", "events", List.of("service ASC", "timestamp DESC", "id DESC")),
				Arguments.of("idx_events_service_type_timestamp_id", "events", List.of("service ASC", "type ASC", "timestamp DESC", "id DESC")),
				Arguments.of("idx_incidents_created_at_id", "incidents", List.of("created_at DESC", "id DESC")),
				Arguments.of("idx_incidents_service_created_at_id", "incidents", List.of("service ASC", "created_at DESC", "id DESC")),
				Arguments.of("idx_incidents_service_status_created_at_id", "incidents", List.of("service ASC", "status ASC", "created_at DESC", "id DESC")));
	}
}
