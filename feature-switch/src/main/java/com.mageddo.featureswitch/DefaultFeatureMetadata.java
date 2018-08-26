package com.mageddo.featureswitch;

import java.util.Collections;
import java.util.Map;

public class DefaultFeatureMetadata implements FeatureMetadata {

	private Feature feature;
	private Map<String, String> parameters;

	public DefaultFeatureMetadata(Feature feature, Map<String, String> parameters) {
		this.feature = feature;
		this.parameters = parameters;
	}

	@Override
	public Feature feature() {
		return feature;
	}

	@Override
	public Map<String, String> parameters() {
		return Collections.unmodifiableMap(parameters);
	}

	@Override
	public void set(String k, String v){
		parameters.put(k, v);
	}

	@Override
	public String get(String k){
		return parameters.get(k);
	}

	@Override
	public void remove(String k){
		parameters.remove(k);
	}
}
