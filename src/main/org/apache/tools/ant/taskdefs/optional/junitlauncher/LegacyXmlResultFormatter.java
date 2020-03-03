/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.tools.ant.taskdefs.optional.junitlauncher;

import org.apache.tools.ant.util.DOMElementWriter;
import org.apache.tools.ant.util.DateUtils;
import org.apache.tools.ant.util.StringUtils;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link TestResultFormatter} which generates an XML report of the tests. The generated XML reports
 * conforms to the schema of the XML that was generated by the {@code junit} task's XML
 * report formatter and can be used by the {@code junitreport} task
 */
class LegacyXmlResultFormatter extends AbstractJUnitResultFormatter implements TestResultFormatter {

    private static final double ONE_SECOND = 1000.0;

    private OutputStream outputStream;
    private final Map<TestIdentifier, Stats> testIds = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, Optional<String>> skipped = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, Optional<Throwable>> failed = new ConcurrentHashMap<>();
    private final Map<TestIdentifier, Optional<Throwable>> aborted = new ConcurrentHashMap<>();

    private TestPlan testPlan;
    private long testPlanStartedAt = -1;
    private long testPlanEndedAt = -1;
    private final AtomicLong numTestsRun = new AtomicLong(0);
    private final AtomicLong numTestsFailed = new AtomicLong(0);
    private final AtomicLong numTestsSkipped = new AtomicLong(0);
    private final AtomicLong numTestsAborted = new AtomicLong(0);


    @Override
    public void testPlanExecutionStarted(final TestPlan testPlan) {
        this.testPlan = testPlan;
        this.testPlanStartedAt = System.currentTimeMillis();
    }

    @Override
    public void testPlanExecutionFinished(final TestPlan testPlan) {
        this.testPlanEndedAt = System.currentTimeMillis();
        // format and print out the result
        try {
            new XMLReportWriter().write();
        } catch (IOException | XMLStreamException e) {
            handleException(e);
        }
    }

    @Override
    public void dynamicTestRegistered(final TestIdentifier testIdentifier) {
        // nothing to do
    }

    @Override
    public void executionSkipped(final TestIdentifier testIdentifier, final String reason) {
        final long currentTime = System.currentTimeMillis();
        this.numTestsSkipped.incrementAndGet();
        this.skipped.put(testIdentifier, Optional.ofNullable(reason));
        // a skipped test is considered started and ended now
        final Stats stats = new Stats(testIdentifier, currentTime);
        stats.endedAt = currentTime;
        this.testIds.put(testIdentifier, stats);
    }

    @Override
    public void executionStarted(final TestIdentifier testIdentifier) {
        final long currentTime = System.currentTimeMillis();
        this.testIds.putIfAbsent(testIdentifier, new Stats(testIdentifier, currentTime));
        if (testIdentifier.isTest()) {
            this.numTestsRun.incrementAndGet();
        }
    }

    @Override
    public void executionFinished(final TestIdentifier testIdentifier, final TestExecutionResult testExecutionResult) {
        final long currentTime = System.currentTimeMillis();
        final Stats stats = this.testIds.get(testIdentifier);
        if (stats != null) {
            stats.endedAt = currentTime;
        }
        switch (testExecutionResult.getStatus()) {
            case SUCCESSFUL: {
                break;
            }
            case ABORTED: {
                this.numTestsAborted.incrementAndGet();
                this.aborted.put(testIdentifier, testExecutionResult.getThrowable());
                break;
            }
            case FAILED: {
                this.numTestsFailed.incrementAndGet();
                this.failed.put(testIdentifier, testExecutionResult.getThrowable());
                break;
            }
        }
    }

    @Override
    public void reportingEntryPublished(final TestIdentifier testIdentifier, final ReportEntry entry) {
        // nothing to do
    }

    @Override
    public void setDestination(final OutputStream os) {
        this.outputStream = os;
    }

    private final class Stats {
        @SuppressWarnings("unused")
        private final TestIdentifier testIdentifier;
        private final long startedAt;
        private long endedAt;

        private Stats(final TestIdentifier testIdentifier, final long startedAt) {
            this.testIdentifier = testIdentifier;
            this.startedAt = startedAt;
        }
    }

    private final class XMLReportWriter {

        private static final String ELEM_TESTSUITE = "testsuite";
        private static final String ELEM_PROPERTIES = "properties";
        private static final String ELEM_PROPERTY = "property";
        private static final String ELEM_TESTCASE = "testcase";
        private static final String ELEM_SKIPPED = "skipped";
        private static final String ELEM_FAILURE = "failure";
        private static final String ELEM_ABORTED = "aborted";
        private static final String ELEM_SYSTEM_OUT = "system-out";
        private static final String ELEM_SYSTEM_ERR = "system-err";


        private static final String ATTR_CLASSNAME = "classname";
        private static final String ATTR_NAME = "name";
        private static final String ATTR_VALUE = "value";
        private static final String ATTR_TIME = "time";
        private static final String ATTR_TIMESTAMP = "timestamp";
        private static final String ATTR_NUM_ABORTED = "aborted";
        private static final String ATTR_NUM_FAILURES = "failures";
        private static final String ATTR_NUM_TESTS = "tests";
        private static final String ATTR_NUM_SKIPPED = "skipped";
        private static final String ATTR_MESSAGE = "message";
        private static final String ATTR_TYPE = "type";

        void write() throws XMLStreamException, IOException {
            final XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(outputStream, "UTF-8");
            try {
                writer.writeStartDocument();
                writeTestSuite(writer);
                writer.writeEndDocument();
            } finally {
                writer.close();
            }
        }

        void writeTestSuite(final XMLStreamWriter writer) throws XMLStreamException, IOException {
            // write the testsuite element
            writer.writeStartElement(ELEM_TESTSUITE);
            final String testsuiteName = determineTestSuiteName();
            writer.writeAttribute(ATTR_NAME, testsuiteName);
            // time taken for the tests execution
            writer.writeAttribute(ATTR_TIME, String.valueOf((testPlanEndedAt - testPlanStartedAt) / ONE_SECOND));
            // add the timestamp of report generation
            final String timestamp = DateUtils.format(new Date(), DateUtils.ISO8601_DATETIME_PATTERN);
            writer.writeAttribute(ATTR_TIMESTAMP, timestamp);
            writer.writeAttribute(ATTR_NUM_TESTS, String.valueOf(numTestsRun.longValue()));
            writer.writeAttribute(ATTR_NUM_FAILURES, String.valueOf(numTestsFailed.longValue()));
            writer.writeAttribute(ATTR_NUM_SKIPPED, String.valueOf(numTestsSkipped.longValue()));
            writer.writeAttribute(ATTR_NUM_ABORTED, String.valueOf(numTestsAborted.longValue()));

            // write the properties
            writeProperties(writer);
            // write the tests
            writeTestCase(writer);
            writeSysOut(writer);
            writeSysErr(writer);
            // end the testsuite
            writer.writeEndElement();
        }

        void writeProperties(final XMLStreamWriter writer) throws XMLStreamException {
            final Properties properties = LegacyXmlResultFormatter.this.context.getProperties();
            if (properties == null || properties.isEmpty()) {
                return;
            }
            writer.writeStartElement(ELEM_PROPERTIES);
            for (final String prop : properties.stringPropertyNames()) {
                writer.writeStartElement(ELEM_PROPERTY);
                writer.writeAttribute(ATTR_NAME, prop);
                writer.writeAttribute(ATTR_VALUE, properties.getProperty(prop));
                writer.writeEndElement();
            }
            writer.writeEndElement();
        }

        void writeTestCase(final XMLStreamWriter writer) throws XMLStreamException {
            for (final Map.Entry<TestIdentifier, Stats> entry : testIds.entrySet()) {
                final TestIdentifier testId = entry.getKey();
                if (!testId.isTest() && !failed.containsKey(testId)) {
                    // only interested in test methods unless there was a failure,
                    // in which case we want the exception reported
                    // (https://bz.apache.org/bugzilla/show_bug.cgi?id=63850)
                    continue;
                }
                final String classname = findClassnameOrId(testId);
                writer.writeStartElement(ELEM_TESTCASE);
                writer.writeAttribute(ATTR_CLASSNAME, classname);
                writer.writeAttribute(ATTR_NAME, testId.getLegacyReportingName());
                final Stats stats = entry.getValue();
                writer.writeAttribute(ATTR_TIME, String.valueOf((stats.endedAt - stats.startedAt) / ONE_SECOND));
                // skipped element if the test was skipped
                writeSkipped(writer, testId);
                // failed element if the test failed
                writeFailed(writer, testId);
                // aborted element if the test was aborted
                writeAborted(writer, testId);

                writer.writeEndElement();
            }
        }

        private String findClassnameOrId(TestIdentifier testId) {
            // try to find the associated class of this test, if not found use some id to see test in the reports 
            Optional<ClassSource> parentClassSource = Optional.empty();
            if (!testId.isTest()) {
                parentClassSource = findFirstClassSource(testId);
            }
            
            if (!parentClassSource.isPresent()) {
                parentClassSource = findFirstParentClassSource(testId);
            }
            
            return parentClassSource.map(ClassSource::getClassName).orElse(testId.getUniqueId());
        }

        private void writeSkipped(final XMLStreamWriter writer, final TestIdentifier testIdentifier) throws XMLStreamException {
            if (!skipped.containsKey(testIdentifier)) {
                return;
            }
            writer.writeStartElement(ELEM_SKIPPED);
            final Optional<String> reason = skipped.get(testIdentifier);
            if (reason.isPresent()) {
                writer.writeAttribute(ATTR_MESSAGE, reason.get());
            }
            writer.writeEndElement();
        }

        private void writeFailed(final XMLStreamWriter writer, final TestIdentifier testIdentifier) throws XMLStreamException {
            if (!failed.containsKey(testIdentifier)) {
                return;
            }
            writer.writeStartElement(ELEM_FAILURE);
            final Optional<Throwable> cause = failed.get(testIdentifier);
            if (cause.isPresent()) {
                final Throwable t = cause.get();
                final String message = t.getMessage();
                if (message != null && !message.trim().isEmpty()) {
                    writer.writeAttribute(ATTR_MESSAGE, message);
                }
                writer.writeAttribute(ATTR_TYPE, t.getClass().getName());
                // write out the stacktrace
                writer.writeCData(StringUtils.getStackTrace(t));
            }
            writer.writeEndElement();
        }

        private void writeAborted(final XMLStreamWriter writer, final TestIdentifier testIdentifier) throws XMLStreamException {
            if (!aborted.containsKey(testIdentifier)) {
                return;
            }
            writer.writeStartElement(ELEM_ABORTED);
            final Optional<Throwable> cause = aborted.get(testIdentifier);
            if (cause.isPresent()) {
                final Throwable t = cause.get();
                final String message = t.getMessage();
                if (message != null && !message.trim().isEmpty()) {
                    writer.writeAttribute(ATTR_MESSAGE, message);
                }
                writer.writeAttribute(ATTR_TYPE, t.getClass().getName());
                // write out the stacktrace
                writer.writeCData(StringUtils.getStackTrace(t));
            }
            writer.writeEndElement();
        }

        private void writeSysOut(final XMLStreamWriter writer) throws XMLStreamException, IOException {
            if (!LegacyXmlResultFormatter.this.hasSysOut()) {
                return;
            }
            writer.writeStartElement(ELEM_SYSTEM_OUT);
            try (final Reader reader = LegacyXmlResultFormatter.this.getSysOutReader()) {
                writeCharactersFrom(reader, writer);
            }
            writer.writeEndElement();
        }

        private void writeSysErr(final XMLStreamWriter writer) throws XMLStreamException, IOException {
            if (!LegacyXmlResultFormatter.this.hasSysErr()) {
                return;
            }
            writer.writeStartElement(ELEM_SYSTEM_ERR);
            try (final Reader reader = LegacyXmlResultFormatter.this.getSysErrReader()) {
                writeCharactersFrom(reader, writer);
            }
            writer.writeEndElement();
        }

        private void writeCharactersFrom(final Reader reader, final XMLStreamWriter writer) throws IOException, XMLStreamException {
            final char[] chars = new char[1024];
            int numRead = -1;
            while ((numRead = reader.read(chars)) != -1) {
                // although it's called a DOMElementWriter, the encode method is just a
                // straight forward XML util method which doesn't concern about whether
                // DOM, SAX, StAX semantics.
                // TODO: Perhaps make it a static method
                final String encoded = new DOMElementWriter().encode(new String(chars, 0, numRead));
                writer.writeCharacters(encoded);
            }
        }

        private String determineTestSuiteName() {
            // this is really a hack to try and match the expectations of the XML report in JUnit4.x
            // world. In JUnit5, the TestPlan doesn't have a name and a TestPlan (for which this is a
            // listener) can have numerous tests within it
            final Set<TestIdentifier> roots = testPlan.getRoots();
            if (roots.isEmpty()) {
                return "UNKNOWN";
            }
            for (final TestIdentifier root : roots) {
                final Optional<ClassSource> classSource = findFirstClassSource(root);
                if (classSource.isPresent()) {
                    return classSource.get().getClassName();
                }
            }
            return "UNKNOWN";
        }

        private Optional<ClassSource> findFirstClassSource(final TestIdentifier root) {
            if (root.getSource().isPresent()) {
                final TestSource source = root.getSource().get();
                if (source instanceof ClassSource) {
                    return Optional.of((ClassSource) source);
                }
            }
            for (final TestIdentifier child : testPlan.getChildren(root)) {
                final Optional<ClassSource> classSource = findFirstClassSource(child);
                if (classSource.isPresent()) {
                    return classSource;
                }
            }
            return Optional.empty();
        }

        private Optional<ClassSource> findFirstParentClassSource(final TestIdentifier testId) {
            final Optional<TestIdentifier> parent = testPlan.getParent(testId);
            if (!parent.isPresent() || !parent.get().getSource().isPresent()) {
                return Optional.empty();
            }
            final TestSource parentSource = parent.get().getSource().get();
            return parentSource instanceof ClassSource ? Optional.of((ClassSource) parentSource)
                    : findFirstParentClassSource(parent.get());
        }
    }

}
