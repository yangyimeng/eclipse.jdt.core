package org.eclipse.jdt.internal.compiler;

/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
/**
 * This is the public entry point to resolve type hierarchies.
 *
 * When requesting additional types from the name environment, the resolver
 * accepts all forms (binary, source & compilation unit) for additional types.
 *
 * Side notes: Binary types already know their resolved supertypes so this
 * only makes sense for source types. Even though the compiler finds all binary
 * types to complete the hierarchy of a given source type, is there any reason
 * why the requestor should be informed that binary type X subclasses Y &
 * implements I & J?
 */

import org.eclipse.jdt.internal.compiler.env.*;
import org.eclipse.jdt.internal.compiler.impl.*;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.lookup.*;
import org.eclipse.jdt.internal.compiler.parser.*;
import org.eclipse.jdt.internal.compiler.problem.*;
import org.eclipse.jdt.internal.compiler.util.*;

import java.util.Locale;
import java.util.Map;

public class HierarchyResolver implements ITypeRequestor {
	IHierarchyRequestor requestor;
	LookupEnvironment lookupEnvironment;

	private int typeIndex;
	private IGenericType[] typeModels;
	private ReferenceBinding[] typeBindings;
	private ReferenceBinding focusType;
	private CompilerOptions options;
	
/**
 * A wrapper around the simple name of a type that is missing.
 */
public class MissingType implements IGenericType {
	public String simpleName;
	
	public MissingType(String simpleName) {
		this.simpleName = simpleName;
	}
	
	/*
	 * @see IGenericType#getModifiers()
	 */
	public int getModifiers() {
		return 0;
	}

	/*
	 * @see IGenericType#isBinaryType()
	 */
	public boolean isBinaryType() {
		return false;
	}

	/*
	 * @see IGenericType#isClass()
	 */
	public boolean isClass() {
		return false;
	}

	/*
	 * @see IGenericType#isInterface()
	 */
	public boolean isInterface() {
		return false;
	}

	/*
	 * @see IDependent#getFileName()
	 */
	public char[] getFileName() {
		return null;
	}
	
	public String toString() {
		return "Missing type: " + this.simpleName; //$NON-NLS-1$
	}

}
	
public HierarchyResolver(
	INameEnvironment nameEnvironment,
	IErrorHandlingPolicy policy,
	Map settings,
	IHierarchyRequestor requestor,
	IProblemFactory problemFactory) {

	// create a problem handler given a handling policy
	options = settings == null ? new CompilerOptions() : new CompilerOptions(settings);
	ProblemReporter problemReporter = new ProblemReporter(policy, options, problemFactory);
	this.lookupEnvironment = new LookupEnvironment(this, options, problemReporter, nameEnvironment);
	this.requestor = requestor;

	this.typeIndex = -1;
	this.typeModels = new IGenericType[5];
	this.typeBindings = new ReferenceBinding[5];
}
public HierarchyResolver(INameEnvironment nameEnvironment, Map settings, IHierarchyRequestor requestor, IProblemFactory problemFactory) {
	this(
		nameEnvironment,
		DefaultErrorHandlingPolicies.exitAfterAllProblems(),
		settings,
		requestor,
		problemFactory);
}
/**
 * Add an additional binary type
 */

public void accept(IBinaryType binaryType, PackageBinding packageBinding) {
	BinaryTypeBinding typeBinding = lookupEnvironment.createBinaryTypeFrom(binaryType, packageBinding);
	
	// fault in its hierarchy...
	try {
		typeBinding.superclass();
		typeBinding.superInterfaces();
	} catch (AbortCompilation e) {
		return;
	}
	
	remember(binaryType, typeBinding);
}
/**
 * Add an additional compilation unit.
 */

public void accept(ICompilationUnit sourceUnit) {
	//System.out.println("Cannot accept compilation units inside the HierarchyResolver.");
	lookupEnvironment.problemReporter.abortDueToInternalError(
		new StringBuffer(Util.bind("accept.cannot")) //$NON-NLS-1$
			.append(sourceUnit.getFileName())
			.toString());
}
/**
 * Add additional source types
 */
public void accept(ISourceType[] sourceTypes, PackageBinding packageBinding) {
	// find most enclosing type first (needed when explicit askForType(...) is done 
	// with a member type (e.g. p.A$B))
	ISourceType sourceType = sourceTypes[0];
	while (sourceType.getEnclosingType() != null)
		sourceType = sourceType.getEnclosingType();
	
	// build corresponding compilation unit
	CompilationResult result = new CompilationResult(sourceType.getFileName(), 1, 1);
	CompilationUnitDeclaration unit =
		SourceTypeConverter.buildCompilationUnit(new ISourceType[] {sourceType}, false, true, lookupEnvironment.problemReporter, result);
		
	// build bindings
	if (unit != null) {
		lookupEnvironment.buildTypeBindings(unit);
		rememberWithMemberTypes(sourceType, unit.types[0].binding);
		lookupEnvironment.completeTypeBindings(unit, false);
	}
}
/*
 * Find the super class of the given type in the cache.
 * Returns a MissingType if the class is not found,
 * or null if type has no super class.
 */
private IGenericType findSuperClass(IGenericType type, ReferenceBinding typeBinding) {
	ReferenceBinding superBinding = typeBinding.superclass();
	if (superBinding != null) {
		if (superBinding.id == TypeIds.T_JavaLangObject && typeBinding.isHierarchyInconsistent()) {
			char[]superclassName;
			char separator;
			if (type instanceof IBinaryType) {
				superclassName = ((IBinaryType)type).getSuperclassName();
				separator = '/';
			} else if (type instanceof ISourceType) {
				superclassName = ((ISourceType)type).getSuperclassName();
				separator = '.';
			} else {
				return null;
			}
			if (superclassName == null) return null;
			int lastSeparator = CharOperation.lastIndexOf(separator, superclassName);
			char[] simpleName = lastSeparator == -1 ? superclassName : CharOperation.subarray(superclassName, lastSeparator+1, superclassName.length);
			return new MissingType(new String(simpleName));
		} else {
			for (int t = typeIndex; t >= 0; t--) {
				if (typeBindings[t] == superBinding) {
					return typeModels[t];
				}
			}
		}
	} 
	return null;
}
/*
 * Find the super interfaces of the given type in the cache.
 * Returns a MissingType if the interface is not found.
 */
private IGenericType[] findSuperInterfaces(IGenericType type, ReferenceBinding typeBinding) {
	char[][] superInterfaceNames;
	char separator;
	if (type instanceof IBinaryType) {
		superInterfaceNames = ((IBinaryType)type).getInterfaceNames();
		separator = '/';
	} else if (type instanceof ISourceType) {
		superInterfaceNames = ((ISourceType)type).getInterfaceNames();
		separator = '.';
	} else {
		return null;
	}
	
	ReferenceBinding[] interfaceBindings = typeBinding.superInterfaces();
	int bindingIndex = 0;
	int bindingLength = interfaceBindings.length;
	int length = superInterfaceNames == null ? 0 : superInterfaceNames.length;
	IGenericType[] superinterfaces = new IGenericType[length];
	next : for (int i = 0; i < length; i++) {
		char[] superInterfaceName = superInterfaceNames[i];
		int lastSeparator = CharOperation.lastIndexOf(separator, superInterfaceName);
		char[] simpleName = lastSeparator == -1 ? superInterfaceName : CharOperation.subarray(superInterfaceName, lastSeparator+1, superInterfaceName.length);
		if (bindingIndex < bindingLength) {
			ReferenceBinding interfaceBinding = interfaceBindings[bindingIndex];

			// ensure that the binding corresponds to the interface defined by the user
			char[][] compoundName = interfaceBinding.compoundName;
			if (CharOperation.equals(simpleName, compoundName[compoundName.length-1])) {
				bindingIndex++;
				for (int t = typeIndex; t >= 0; t--) {
					if (typeBindings[t] == interfaceBinding) {
						superinterfaces[i] = typeModels[t];
						continue next;
					}
				}
			}
		}
		superinterfaces[i] = new MissingType(new String(simpleName));
	}
	return superinterfaces;
}
private void remember(IGenericType suppliedType, ReferenceBinding typeBinding) {
	if (typeBinding == null) return;
	
	if (++typeIndex == typeModels.length) {
		System.arraycopy(typeModels, 0, typeModels = new IGenericType[typeIndex * 2], 0, typeIndex);
		System.arraycopy(typeBindings, 0, typeBindings = new ReferenceBinding[typeIndex * 2], 0, typeIndex);
	}
	typeModels[typeIndex] = suppliedType;
	typeBindings[typeIndex] = typeBinding;
}
private void rememberWithMemberTypes(TypeDeclaration typeDeclaration, HierarchyType enclosingType, ICompilationUnit unit) {

	if (typeDeclaration.binding == null) return;

	HierarchyType hierarchyType = new HierarchyType(
		enclosingType, 
		!typeDeclaration.isInterface(),
		typeDeclaration.name,
		typeDeclaration.binding.modifiers,
		unit);
	remember(hierarchyType, typeDeclaration.binding);

	// propagate into member types
	if (typeDeclaration.memberTypes == null) return;
	MemberTypeDeclaration[] memberTypes = typeDeclaration.memberTypes;
	for (int i = 0, max = memberTypes.length; i < max; i++){
		rememberWithMemberTypes(memberTypes[i], hierarchyType, unit);
	}
}
private void rememberWithMemberTypes(ISourceType suppliedType, ReferenceBinding typeBinding) {
	if (typeBinding == null) return;

	remember(suppliedType, typeBinding);

	ISourceType[] memberTypes = suppliedType.getMemberTypes();
	if (memberTypes == null) return;
	for (int m = memberTypes.length; --m >= 0;) {
		ISourceType memberType = memberTypes[m];
		rememberWithMemberTypes(memberType, typeBinding.getMemberType(memberType.getName()));
	}
}
private void reportHierarchy() {
	for (int current = typeIndex; current >= 0; current--) {
		IGenericType suppliedType = typeModels[current];
		ReferenceBinding typeBinding = typeBindings[current];

		if (!subOrSuperOfFocus(typeBinding)) {
			continue; // ignore types outside of hierarchy
		}

		IGenericType superclass;
		if (typeBinding.isInterface()){ // do not connect interfaces to Object
			superclass = null;
		} else {
			superclass = this.findSuperClass(suppliedType, typeBinding);
		}
		IGenericType[] superinterfaces = this.findSuperInterfaces(suppliedType, typeBinding);
		
		requestor.connect(suppliedType, superclass, superinterfaces);
	}
}
private void reset(){
	lookupEnvironment.reset();

	this.typeIndex = -1;
	this.typeModels = new IGenericType[5];
	this.typeBindings = new ReferenceBinding[5];
}
/**
 * Resolve the supertypes for the supplied source types.
 * Inform the requestor of the resolved supertypes for each
 * supplied source type using:
 *    connect(ISourceType suppliedType, IGenericType superclass, IGenericType[] superinterfaces)
 *
 * Also inform the requestor of the supertypes of each
 * additional requested super type which is also a source type
 * instead of a binary type.
 */

public void resolve(IGenericType[] suppliedTypes) {
	resolve(suppliedTypes, null);
}
/**
 * Resolve the supertypes for the supplied source types.
 * Inform the requestor of the resolved supertypes for each
 * supplied source type using:
 *    connect(ISourceType suppliedType, IGenericType superclass, IGenericType[] superinterfaces)
 *
 * Also inform the requestor of the supertypes of each
 * additional requested super type which is also a source type
 * instead of a binary type.
 */

public void resolve(IGenericType[] suppliedTypes, ICompilationUnit[] sourceUnits) {
	try {
		int suppliedLength = suppliedTypes == null ? 0 : suppliedTypes.length;
		int sourceLength = sourceUnits == null ? 0 : sourceUnits.length;
		CompilationUnitDeclaration[] units = new CompilationUnitDeclaration[suppliedLength + sourceLength];
		
		// build type bindings
		for (int i = 0; i < suppliedLength; i++) {
			if (suppliedTypes[i].isBinaryType()) {
				IBinaryType binaryType = (IBinaryType) suppliedTypes[i];
				try {
					remember(binaryType, lookupEnvironment.cacheBinaryType(binaryType, false));
				} catch (AbortCompilation e) {
					// classpath problem for this type: ignore
				}
			} else {
				// must start with the top level type
				ISourceType topLevelType = (ISourceType) suppliedTypes[i];
				while (topLevelType.getEnclosingType() != null)
					topLevelType = topLevelType.getEnclosingType();
				CompilationResult result = new CompilationResult(topLevelType.getFileName(), i, suppliedLength);
				units[i] = SourceTypeConverter.buildCompilationUnit(new ISourceType[]{topLevelType}, false, true, lookupEnvironment.problemReporter, result);
				if (units[i] != null) {
					try {
						lookupEnvironment.buildTypeBindings(units[i]);
					} catch (AbortCompilation e) {
						// classpath problem for this type: ignore
					}
				}
			}
		}
		for (int i = 0; i < sourceLength; i++){
			ICompilationUnit sourceUnit = sourceUnits[i];
			CompilationResult unitResult = new CompilationResult(sourceUnit, suppliedLength+i, suppliedLength+sourceLength); 
			Parser parser = new Parser(lookupEnvironment.problemReporter, true, options.assertMode);
			CompilationUnitDeclaration parsedUnit = parser.dietParse(sourceUnit, unitResult);
			if (parsedUnit != null) {
				units[suppliedLength+i] = parsedUnit;
				lookupEnvironment.buildTypeBindings(parsedUnit);
			}
		}
		
		// complete type bindings (ie. connect super types) and remember them
		for (int i = 0; i < suppliedLength; i++) {
			if (!suppliedTypes[i].isBinaryType()) { // note that binary types have already been remembered above
				CompilationUnitDeclaration parsedUnit = units[i];
				if (parsedUnit != null) {
					// must start with the top level type
					ISourceType topLevelType = (ISourceType) suppliedTypes[i];
					suppliedTypes[i] = null; // no longer needed pass this point				
					while (topLevelType.getEnclosingType() != null)
						topLevelType = topLevelType.getEnclosingType();
					try {
						lookupEnvironment.completeTypeBindings(parsedUnit, false);
						rememberWithMemberTypes(topLevelType, parsedUnit.types[0].binding);
					} catch (AbortCompilation e) {
						// classpath problem for this type: ignore
					}
				}
			}
		}
		for (int i = 0; i < sourceLength; i++) {
			CompilationUnitDeclaration parsedUnit = units[suppliedLength+i];
			if (parsedUnit != null) {
				lookupEnvironment.completeTypeBindings(parsedUnit, false);
				int typeCount = parsedUnit.types == null ? 0 : parsedUnit.types.length;
				ICompilationUnit sourceUnit = sourceUnits[i];
				sourceUnits[i] = null; // no longer needed pass this point
				for (int j = 0; j < typeCount; j++){
					rememberWithMemberTypes(parsedUnit.types[j], null, sourceUnit);
				}
			}
		}

		reportHierarchy();
		
	} catch (ClassCastException e){ // work-around for 1GF5W1S - can happen in case duplicates are fed to the hierarchy with binaries hiding sources
	} catch (AbortCompilation e) { // ignore this exception for now since it typically means we cannot find java.lang.Object
	} finally {
		reset();
	}
}
/**
 * Resolve the supertypes for the supplied source type.
 * Inform the requestor of the resolved supertypes using:
 *    connect(ISourceType suppliedType, IGenericType superclass, IGenericType[] superinterfaces)
 */

public void resolve(IGenericType suppliedType) {
	try {
		if (suppliedType.isBinaryType()) {
			remember(suppliedType, lookupEnvironment.cacheBinaryType((IBinaryType) suppliedType));
		} else {
			// must start with the top level type
			ISourceType topLevelType = (ISourceType) suppliedType;
			while (topLevelType.getEnclosingType() != null)
				topLevelType = topLevelType.getEnclosingType();
			CompilationResult result = new CompilationResult(topLevelType.getFileName(), 1, 1);
			CompilationUnitDeclaration unit =
				SourceTypeConverter.buildCompilationUnit(new ISourceType[]{topLevelType}, false, true, lookupEnvironment.problemReporter, result);

			if (unit != null) {
				lookupEnvironment.buildTypeBindings(unit);
				rememberWithMemberTypes(topLevelType, unit.types[0].binding);

				lookupEnvironment.completeTypeBindings(unit, false);
			}
		}
		reportHierarchy();
	} catch (AbortCompilation e) { // ignore this exception for now since it typically means we cannot find java.lang.Object
	} finally {
		reset();
	}
}
/**
 * Set the focus type (ie. the type that this resolver is computing the hierarch for.
 * Returns the binding of this focus type or null if it could not be found.
 */
public ReferenceBinding setFocusType(char[][] compoundName) {
	if (compoundName == null || this.lookupEnvironment == null) return null;
	this.focusType = this.lookupEnvironment.askForType(compoundName);
	return this.focusType;
}
private boolean subOrSuperOfFocus(ReferenceBinding typeBinding) {
	if (this.focusType == null) return true; // accept all types (case of hierarchy in a region)
	if (this.subTypeOfType(this.focusType, typeBinding)) return true;
	if (this.subTypeOfType(typeBinding, this.focusType)) return true;
	return false;
}
private boolean subTypeOfType(ReferenceBinding subType, ReferenceBinding typeBinding) {
	if (typeBinding == null || subType == null) return false;
	if (subType == typeBinding) return true;
	ReferenceBinding superclass = subType.superclass();
	if (superclass != null && superclass.id == TypeIds.T_JavaLangObject && subType.isHierarchyInconsistent()) return false;
	if (this.subTypeOfType(superclass, typeBinding)) return true;
	ReferenceBinding[] superInterfaces = subType.superInterfaces();
	if (superInterfaces != null) {
		for (int i = 0, length = superInterfaces.length; i < length; i++) {
			if (this.subTypeOfType(superInterfaces[i], typeBinding)) return true;
		} 
	}
	return false;
}
}
