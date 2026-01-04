package com.doubledump.model;

import java.util.List;

public record DuplicateGroupRecord(String hash, int count, String keep, List<String> files) {
}
