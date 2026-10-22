package com.acme.fraud.api;

import java.util.List;

record PageResponse<T>(List<T> items, int page, int size, long total) {}
