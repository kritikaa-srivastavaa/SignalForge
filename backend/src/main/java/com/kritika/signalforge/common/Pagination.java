package com.kritika.signalforge.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class Pagination {

	private Pagination() {
	}

	public static PageRequest request(int page, int size, String timeField) {
		int effectiveSize = size <= 0 ? 20 : Math.min(size, 100);
		return PageRequest.of(Math.max(page, 0), effectiveSize,
				Sort.by(Sort.Direction.DESC, timeField, "id"));
	}

	public static String filter(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
