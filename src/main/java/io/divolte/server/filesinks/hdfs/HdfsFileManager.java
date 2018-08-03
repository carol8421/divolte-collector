/*
 * Copyright 2018 GoDataDriven B.V.
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

package io.divolte.server.filesinks.hdfs;

import com.google.common.base.MoreObjects;
import com.google.common.io.Closeables;
import io.divolte.server.AvroRecordBuffer;
import io.divolte.server.config.FileSinkConfiguration;
import io.divolte.server.config.HdfsConfiguration;
import io.divolte.server.config.HdfsSinkConfiguration;
import io.divolte.server.config.ValidatedConfiguration;
import io.divolte.server.filesinks.FileManager;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

@ParametersAreNonnullByDefault
public class HdfsFileManager implements FileManager {
    private static final Logger logger = LoggerFactory.getLogger(HdfsFileManager.class);

    private final static String INFLIGHT_EXTENSION = ".partial";

    private final FileSystem hdfs;
    private final short replication;
    private final String workDir;
    private final String publishDir;
    private final Schema writeSchema;

    private HdfsFileManager(final FileSystem hdfs, final short replication, final String workDir, final String publishDir, final Schema schema) {
        this.hdfs = Objects.requireNonNull(hdfs);
        this.workDir = Objects.requireNonNull(workDir);
        this.publishDir = Objects.requireNonNull(publishDir);
        this.writeSchema = Objects.requireNonNull(schema);
        this.replication = replication;
    }

    @Override
    public DivolteFile createFile(final String name) throws IOException {
        return new HdfsDivolteFile(name);
    }

    public static FileManagerFactory newFactory(final ValidatedConfiguration configuration, final String sinkName, final Schema schema) {
        return new HdfsFileManagerFactory(configuration, sinkName, schema);
    }

    public class HdfsDivolteFile implements DivolteFile {
        private final FSDataOutputStream stream;
        private final DataFileWriter<GenericRecord> writer;
        private final Path inflightPath;
        private final Path publishPath;

        @SuppressWarnings("resource")
        HdfsDivolteFile(final String name) throws IOException {
            inflightPath = new Path(workDir, name + INFLIGHT_EXTENSION);
            publishPath = new Path(publishDir, name);

            stream = hdfs.create(inflightPath, replication);

            writer = new DataFileWriter<GenericRecord>(new GenericDatumWriter<>(writeSchema)).create(writeSchema, stream);
            writer.setSyncInterval(1 << 30);
            writer.setFlushOnEveryBlock(true);

            // Sync the file on open to make sure the
            // connection actually works, because
            // HDFS allows file creation even with no
            // datanodes available
            this.stream.hsync();
        }

        @Override
        public void append(final AvroRecordBuffer buffer) throws IOException {
            writer.appendEncoded(buffer.getByteBuffer());
        }

        @Override
        public void closeAndPublish() throws IOException {
            sync();
            writer.close(); // closes underlying stream as well
            if (!hdfs.rename(inflightPath, publishPath)) {
                logger.warn("Failed to publish HDFS file {} to {}.", inflightPath, publishPath);
            }
        }

        @Override
        public void sync() throws IOException {
            // Forces the Avro file to write a block
            writer.sync();

            // Forces a (HDFS) flush and sync on the underlying stream
            stream.hflush();
            stream.hsync();
        }

        @Override
        public void discard() throws IOException {
            Closeables.close(writer, true);

            if (hdfs.exists(inflightPath)) {
                hdfs.delete(inflightPath, false);
            }
        }

        @Override
        public String toString() {
            return MoreObjects
                    .toStringHelper(getClass())
                    .add("inflight file", inflightPath)
                    .add("publish file", publishPath)
                    .toString();
        }
    }

    @ParametersAreNonnullByDefault
    public static class HdfsFileManagerFactory implements FileManager.FileManagerFactory {
        private final ValidatedConfiguration configuration;
        private final String name;
        private final Schema schema;

        private HdfsFileManagerFactory(final ValidatedConfiguration vc, final String name, final Schema schema) {
            this.schema = Objects.requireNonNull(schema);
            this.configuration = Objects.requireNonNull(vc);
            this.name = Objects.requireNonNull(name);
        }

        @Override
        public void verifyFileSystemConfiguration() {
            try {
                final FileSystem hdfs = getFileSystemInstance();

                final String hdfsWorkingDir = configuration.configuration().getSinkConfiguration(name, FileSinkConfiguration.class).fileStrategy.workingDir;
                final String hdfsPublishDir = configuration.configuration().getSinkConfiguration(name, FileSinkConfiguration.class).fileStrategy.publishDir;

                try {
                    if (!hdfs.getFileStatus(new Path(hdfsWorkingDir)).isDirectory()) {
                        throw new RuntimeException("Path for in-flight AVRO records is not a directory: " + hdfsWorkingDir);
                    }
                } catch (final FileNotFoundException e) {
                    throw new RuntimeException("Working directory for in-flight AVRO records does not exist: " + hdfsWorkingDir);
                }

                try {
                    if (!hdfs.getFileStatus(new Path(hdfsPublishDir)).isDirectory()) {
                        throw new RuntimeException("Path for publishing AVRO record is not a directory: " + hdfsPublishDir);
                    }
                } catch (final FileNotFoundException e) {
                    throw new RuntimeException("Directory for publishing AVRO records does not exist: " + hdfsPublishDir);
                }
            } catch (final IOException ioe) {
                /*
                 * It is possible to create a FileSystem instance when HDFS is not available (e.g. NameNode down).
                 * This exception only occurs when there is a configuration error in the URI (e.g. wrong scheme).
                 * So we fail to start up in this case. Below we create the actual HDFS connection, by opening
                 * files. If that fails, we do startup and initiate the regular retry cycle.
                 */
                logger.error("Could not initialize HDFS filesystem or failed to check for existence of publish and / or working directories..", ioe);
                throw new UncheckedIOException("Could not initialize HDFS filesystem.", ioe);
            }
        }

        @Override
        public FileManager create() {
            final short hdfsReplication =
                    configuration.configuration()
                      .getSinkConfiguration(Objects.requireNonNull(name), HdfsSinkConfiguration.class).replication;

            try {
                final String hdfsWorkingDir = configuration.configuration().getSinkConfiguration(name, FileSinkConfiguration.class).fileStrategy.workingDir;
                final String hdfsPublishDir = configuration.configuration().getSinkConfiguration(name, FileSinkConfiguration.class).fileStrategy.publishDir;
                return new HdfsFileManager(getFileSystemInstance(), hdfsReplication, hdfsWorkingDir, hdfsPublishDir, schema);
            } catch (final IOException e) {
                logger.error("Failed to construct HDFS file system instance from verified configuration.");
                throw new UncheckedIOException(e);
            }
        }

        private FileSystem getFileSystemInstance() throws IOException {
            final HdfsConfiguration hdfsConfiguration = configuration.configuration().global.getSinkTypeConfiguration(HdfsConfiguration.class);

            final Configuration hdfsClientConfiguration = hdfsConfiguration.client
                    .map(clientProperties -> {
                        final Configuration configuration = new Configuration(false);
                        for (final String propertyName : clientProperties.stringPropertyNames()) {
                            configuration.set(propertyName, clientProperties.getProperty(propertyName));
                        }
                        return configuration;
                    })
                    .orElse(new Configuration());
            /*
             * The HDFS client creates a JVM shutdown hook, which interferes with our own server shutdown hook.
             * This config option disabled the built in shutdown hook. We call FileSystem.closeAll() ourselves
             * in the server shutdown hook instead.
             */
            hdfsClientConfiguration.setBoolean("fs.automatic.close", false);

            return FileSystem.get(hdfsClientConfiguration);
        }
    }
}
