package org.eclipse.jdt.core.internal.tools.unicode;

import java.util.HashSet;
import java.util.Set;

public class StartEnvironment extends Environment {
	private static final String RESOURCE_FILE_NAME = "start"; //$NON-NLS-1$

	private static enum Category {
		Lu, Ll, Lt, Lm, Lo, Nl, Sc, Pc
	}

	private static final Set<String> categories = new HashSet<>();
	static {
		for (Category c : Category.values()) {
			categories.add(c.name());
		}
	}

	@Override
	public boolean hasCategory(String value) {
		return categories.contains(value);
	}

	@Override
	public String getResourceFileName() {
		return RESOURCE_FILE_NAME;
	}

}
