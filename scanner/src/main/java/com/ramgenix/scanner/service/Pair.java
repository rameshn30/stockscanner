package com.ramgenix.scanner.service;

/**
 * Simple generic Pair class to return two values from a method.
 */
class Pair<K, V> {
	private final K key;
	private final V value;

	public Pair(K key, V value) {
		this.key = key;
		this.value = value;
	}

	public K getKey() {
		return key;
	}

	public V getValue() {
		return value;
	}
}