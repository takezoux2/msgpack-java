//
// MessagePack for Java
//
// Copyright (C) 2009-2011 FURUHASHI Sadayuki
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//
package org.msgpack.template.builder;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;

import org.msgpack.MessageTypeException;
import org.msgpack.annotation.Beans;
import org.msgpack.annotation.Ignore;
import org.msgpack.annotation.Index;
import org.msgpack.annotation.MessagePackBeans;
import org.msgpack.annotation.NotNullable;
import org.msgpack.annotation.Optional;
import org.msgpack.annotation.Required;
import org.msgpack.packer.Packer;
import org.msgpack.template.FieldOption;
import org.msgpack.template.Template;
import org.msgpack.template.TemplateRegistry;
import org.msgpack.unpacker.Unpacker;


/**
 * Class for building java reflection template builder for java beans class.
 * 
 * @author takeshita
 *
 */
public class ReflectionBeansTemplateBuilder extends AbstractTemplateBuilder {

    static class ReflectionBeansFieldEntry extends BeansFieldEntry {
	ReflectionBeansFieldEntry(final BeansFieldEntry entry) {
	    super(entry);
	}

	void write(Packer packer, Object v) throws IOException {
	    packer.write(v);
	}

	void read(Unpacker unpacker, Object to) throws IOException, MessageTypeException, IllegalAccessException {
	    set(to, unpacker.read(getType()));
	}

	public void setNull(Object target) {
	    set(target, null);
	}
    }

    static class ObjectFieldEntry extends ReflectionBeansFieldEntry {
	Template template;

	ObjectFieldEntry(final BeansFieldEntry entry, final Template template) {
	    super(entry);
	    this.template = template;
	}

	@Override
	void write(Packer packer, Object v) throws IOException {
	    template.write(packer, v);
	}

	@Override
	void read(Unpacker unpacker, Object target) throws IOException, MessageTypeException, IllegalAccessException {
	    Class<Object> type = (Class<Object>) getType();
	    Object fieldReference = get(target);
	    Object valueReference = template.read(unpacker, fieldReference);
	    if (valueReference != fieldReference) {
		set(target, valueReference);
	    }
	}
    }

    static class BeansReflectionTemplate<T> implements Template<T> {
	private Class<T> targetClass;

	private ReflectionBeansFieldEntry[] entries = null;

	protected int minimumArrayLength;

	BeansReflectionTemplate(Class<T> targetClass, ReflectionBeansFieldEntry[] entries) {
	    this.targetClass = targetClass;
	    this.entries = entries;
	    this.minimumArrayLength = 0;
	    for (int i = 0; i < entries.length; i++) {
		ReflectionBeansFieldEntry e = entries[i];
		if (e.isRequired() || !e.isNotNullable()) {
		    this.minimumArrayLength = i + 1;
		}
	    }
	}

	@Override
	public
	void write(Packer pk, T v) throws IOException {
	    pk.writeArrayBegin(entries.length);
	    for (ReflectionBeansFieldEntry e : entries) {
		if (!e.isAvailable()) {
		    pk.writeNil();
		    continue;
		}
		Object obj = e.get(v);
		if (obj == null) {
		    if (e.isNotNullable() && !e.isOptional()) {
			throw new MessageTypeException();
		    }
		    pk.writeNil();
		} else {
		    e.write(pk, obj);
		}
	    }
	    pk.writeArrayEnd();
	}

	@Override
	public T read(Unpacker u, T to) throws IOException {
	    try {
		if (to == null) {
		    to = targetClass.newInstance();
		}

		int length = u.readArrayBegin();
		if (length < minimumArrayLength) {
		    throw new MessageTypeException();
		}

		int i;
		for (i = 0; i < minimumArrayLength; i++) {
		    ReflectionBeansFieldEntry e = entries[i];
		    if (!e.isAvailable()) {
			u.readValue(); // TODO #MN
			continue;
		    }

		    if (u.tryReadNil()) {
			if (e.isRequired()) {
			    // Required + nil => exception
			    throw new MessageTypeException();
			} else if (e.isOptional()) {
			    // Optional + nil => keep default value
			} else { // Nullable
				 // Nullable + nil => set null
			    e.setNull(to);
			}
		    } else {
			e.read(u, to);
			// e.set(to, pac.unpack(e.getType()));
		    }
		}

		int max = length < entries.length ? length : entries.length;
		for (; i < max; i++) {
		    ReflectionBeansFieldEntry e = entries[i];
		    if (!e.isAvailable()) {
			u.readValue(); // TODO #MN
			continue;
		    }

		    if (u.tryReadNil()) {
			// this is Optional field becaue i >= minimumArrayLength
			// Optional + nil => keep default value
		    } else {
			e.read(u, to);
			// e.set(to, pac.unpack(e.getType()));
		    }
		}

		// latter entries are all Optional + nil => keep default value

		for (; i < length; i++) {
		    u.readValue();
		}

		u.readArrayEnd();
		return to;
	    } catch (MessageTypeException e) {
		throw e;
	    } catch (IOException e) {
		throw e;
	    } catch (Exception e) {
		throw new MessageTypeException(e);
	    }
	}
    }

    public ReflectionBeansTemplateBuilder(TemplateRegistry registry) {
	super(registry);
    }

    @Override
    public boolean matchType(Type targetType) {
	return AbstractTemplateBuilder.isAnnotated((Class<?>) targetType, Beans.class)
		|| AbstractTemplateBuilder.isAnnotated((Class<?>) targetType, MessagePackBeans.class);
    }

    @Override
    public <T> Template<T> buildTemplate(Class<T> targetClass, FieldEntry[] entries) {
	ReflectionBeansFieldEntry[] beansEntries = new ReflectionBeansFieldEntry[entries.length];
	for (int i = 0; i < entries.length; i++) {
	    BeansFieldEntry e = (BeansFieldEntry) entries[i];
	    Class<?> type = e.getType();
	    if (type.equals(boolean.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(byte.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(short.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(int.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(long.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(float.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else if (type.equals(double.class)) {
		beansEntries[i] = new ReflectionBeansFieldEntry(e);
	    } else {
		Template tmpl = registry.lookup(e.getGenericType(), true);
		beansEntries[i] = new ObjectFieldEntry(e, tmpl);
	    }
	}
	return new BeansReflectionTemplate(targetClass, beansEntries);
    }

    @Override
    public FieldEntry[] readFieldEntries(Class<?> targetClass, FieldOption implicitOption) {
	BeanInfo desc;
	try {
	    desc = Introspector.getBeanInfo(targetClass);
	} catch (IntrospectionException e1) {
	    throw new TemplateBuildException("Class must be java beans class:" + targetClass.getName());
	}

	PropertyDescriptor[] props = desc.getPropertyDescriptors();
	ArrayList<PropertyDescriptor> list = new ArrayList<PropertyDescriptor>();
	for (int i = 0; i < props.length; i++) {
	    PropertyDescriptor pd = props[i];
	    if (!isIgnoreProp(pd)) {
		list.add(pd);
	    }
	}
	props = new PropertyDescriptor[list.size()];
	list.toArray(props);

	BeansFieldEntry[] entries = new BeansFieldEntry[props.length];
	for (int i = 0; i < props.length; i++) {
	    PropertyDescriptor p = props[i];
	    int index = readPropIndex(p);
	    if (index >= 0) {
		if (entries[index] != null) {
		    throw new TemplateBuildException("duplicated index: "
			    + index);
		}
		if (index >= entries.length) {
		    throw new TemplateBuildException("invalid index: " + index);
		}
		entries[index] = new BeansFieldEntry(p);
		props[index] = null;
	    }
	}
	int insertIndex = 0;
	for (int i = 0; i < props.length; i++) {
	    PropertyDescriptor p = props[i];
	    if (p != null) {
		while (entries[insertIndex] != null) {
		    insertIndex++;
		}
		entries[insertIndex] = new BeansFieldEntry(p);
	    }

	}
	for (int i = 0; i < entries.length; i++) {
	    BeansFieldEntry e = entries[i];
	    FieldOption op = readPropOption(e, implicitOption);
	    e.setOption(op);
	}
	return entries;
    }

    private FieldOption readPropOption(BeansFieldEntry e, FieldOption implicitOption) {
	FieldOption forGetter = readMethodOption(e.getPropertyDescriptor().getReadMethod());
	if (forGetter != FieldOption.DEFAULT) {
	    return forGetter;
	}
	FieldOption forSetter = readMethodOption(e.getPropertyDescriptor().getWriteMethod());
	if (forSetter != FieldOption.DEFAULT) {
	    return forSetter;
	} else {
	    return implicitOption;
	}
    }

    private FieldOption readMethodOption(Method method) {
	if (isAnnotated(method, Ignore.class)) {
	    return FieldOption.IGNORE;
	} else if (isAnnotated(method, Required.class)) {
	    return FieldOption.REQUIRED;
	} else if (isAnnotated(method, Optional.class)) {
	    return FieldOption.OPTIONAL;
	} else if (isAnnotated(method, NotNullable.class)) {
	    if (method.getDeclaringClass().isPrimitive()) {
		return FieldOption.REQUIRED;
	    } else {
		return FieldOption.NOTNULLABLE;
	    }
	}
	return FieldOption.DEFAULT;
    }

    private int readPropIndex(PropertyDescriptor desc) {
	int forGetter = readMethodIndex(desc.getReadMethod());
	if (forGetter >= 0) {
	    return forGetter;
	}
	int forSetter = readMethodIndex(desc.getWriteMethod());
	return forSetter;
    }

    private int readMethodIndex(Method method) {
	Index a = method.getAnnotation(Index.class);
	if (a == null) {
	    return -1;
	} else {
	    return a.value();
	}
    }

    boolean isIgnoreProp(PropertyDescriptor desc) {
	if (desc == null)
	    return true;
	Method getter = desc.getReadMethod();
	Method setter = desc.getWriteMethod();
	return getter == null || setter == null
		|| !Modifier.isPublic(getter.getModifiers())
		|| !Modifier.isPublic(setter.getModifiers())
		|| isAnnotated(getter, Ignore.class)
		|| isAnnotated(setter, Ignore.class);
    }
}