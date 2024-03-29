/*
 * Copyright [2021] [ACoLi Lab, Prof. Dr. Chiarcos, Christian Faeth, Goethe University Frankfurt]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acoli.fintan.genericIO;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import org.acoli.fintan.core.FintanStreamComponentFactory;
import org.acoli.fintan.core.StreamTransformerGenericIO;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Duplicates the contents of default InputStream to all attached OutputStreams.
 * 
 * Throws IOException, if a named InputStream is set. Can only take  a single input.
 * 
 * @author Christian Faeth {@literal faeth@em.uni-frankfurt.de}
 *
 */
public final class IOStreamDuplicator extends StreamTransformerGenericIO implements FintanStreamComponentFactory {
	
	protected static final Logger LOG = LogManager.getLogger(IOStreamDuplicator.class.getName());
	
	public static final int DEFAULT_BUFFER_SIZE = 8192;
	
	/**
	 * No json parameters necessary.
	 */
	@Override
	public IOStreamDuplicator buildFromJsonConf(ObjectNode conf)
			throws IOException, IllegalArgumentException, ParseException {
		return new IOStreamDuplicator();
	}

	@Override
	public IOStreamDuplicator buildFromCLI(String[] args)
			throws IOException, IllegalArgumentException, ParseException {
		return new IOStreamDuplicator();
	}

	/**
	 * Overrides default method. Only accepts default stream.
	 * 
	 * @throws IOException if named stream is set.
	 */
	@Override
	public void setInputStream(InputStream inputStream, String name) throws IOException {
		if (name == null || FINTAN_DEFAULT_STREAM_NAME.equals(name)) {
			setInputStream(inputStream);
		} else {
			throw new IOException("Only default InputStream is supported for "+IOStreamDuplicator.class.getName());
		}
	}
	
	/**
	 * Override in order to allow underspecified stream names.
	 * Graph names are unnecessary in duplicators.
	 */
	@Override
	public void setOutputStream(OutputStream outputStream) throws IOException {
		super.setOutputStream(outputStream, Integer.toString(outputStream.hashCode()));
	}
	
	/**
	 * Override in order to allow underspecified stream names.
	 * Graph names are unnecessary in duplicators.
	 */
	@Override
	public void setOutputStream(OutputStream outputStream, String name) throws IOException {
			setOutputStream(outputStream);
	}
	
	private void processStream() throws IOException {
		BufferedInputStream in = new BufferedInputStream(getInputStream(), DEFAULT_BUFFER_SIZE);
		int len = 0;
		byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
		while ((len = in.read(buffer)) > -1) {
			byte[] outBuffer;
			if (len < buffer.length) {
				outBuffer = Arrays.copyOf(buffer, len);
			} else {
				outBuffer = buffer;
			}
			for (String name:listOutputStreamNames()) {
				getOutputStream(name).write(outBuffer);
			}
		}

		for (String name:listOutputStreamNames()) {
			getOutputStream(name).close();
		}
	}
	
	@Override
	public void start() {
		run();
	}

	@Override
	public void run() {
		try {
			processStream();
		} catch (Exception e) {
			LOG.error(e, e);
			System.exit(1);
		}
	}

	




	
}