/*
 * Processing of incoming file information from peer
 * Generator and sending of file lists and file data to peer Receiver
 *
 * Copyright (C) 1996-2011 by Andrew Tridgell, Wayne Davison, and others
 * Copyright (C) 2013-2015 Per Lundqvist
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.perlundq.yajsync.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.perlundq.yajsync.channels.AutoFlushableRsyncDuplexChannel;
import com.github.perlundq.yajsync.channels.ChannelEOFException;
import com.github.perlundq.yajsync.channels.ChannelException;
import com.github.perlundq.yajsync.channels.Message;
import com.github.perlundq.yajsync.channels.MessageCode;
import com.github.perlundq.yajsync.channels.MessageHandler;
import com.github.perlundq.yajsync.channels.RsyncInChannel;
import com.github.perlundq.yajsync.channels.RsyncOutChannel;
import com.github.perlundq.yajsync.filelist.FileInfo;
import com.github.perlundq.yajsync.filelist.Filelist;
import com.github.perlundq.yajsync.filelist.RsyncFileAttributes;
import com.github.perlundq.yajsync.filelist.User;
import com.github.perlundq.yajsync.io.FileView;
import com.github.perlundq.yajsync.io.FileViewNotFound;
import com.github.perlundq.yajsync.io.FileViewOpenFailed;
import com.github.perlundq.yajsync.io.FileViewReadError;
import com.github.perlundq.yajsync.text.Text;
import com.github.perlundq.yajsync.text.TextConversionException;
import com.github.perlundq.yajsync.text.TextDecoder;
import com.github.perlundq.yajsync.text.TextEncoder;
import com.github.perlundq.yajsync.util.MD5;
import com.github.perlundq.yajsync.util.PathOps;
import com.github.perlundq.yajsync.util.Rolling;
import com.github.perlundq.yajsync.util.RuntimeInterruptException;
import com.github.perlundq.yajsync.util.StatusResult;

public final class Sender implements RsyncTask, MessageHandler
{
    public static class Builder
    {
        private final ReadableByteChannel _in;
        private final WritableByteChannel _out;
        private final Iterable<Path> _sourceFiles;
        private final byte[] _checksumSeed;
        private boolean _isExitAfterEOF;
        private boolean _isExitEarlyIfEmptyList;
        private boolean _isInterruptible = true;
        private boolean _isPreserveUser;
        private boolean _isReceiveFilterRules;
        private boolean _isSafeFileList = true;
        private boolean _isSendStatistics;
        private Charset _charset = Charset.forName(Text.UTF8_NAME);
        private FileSelection _fileSelection = FileSelection.EXACT;

        public Builder(ReadableByteChannel in,
                       WritableByteChannel out,
                       Iterable<Path> sourceFiles,
                       byte[] checksumSeed)
        {
            assert in != null;
            assert out != null;
            assert sourceFiles != null;
            assert checksumSeed != null;
            _in = in;
            _out = out;
            _sourceFiles = sourceFiles;
            _checksumSeed = checksumSeed;
        }

        public static Builder newServer(ReadableByteChannel in,
                                        WritableByteChannel out,
                                        Iterable<Path> sourceFiles,
                                        byte[] checksumSeed)
        {
            Builder builder = new Builder(in, out, sourceFiles, checksumSeed);
            builder._isReceiveFilterRules = true;
            builder._isSendStatistics = true;
            builder._isExitEarlyIfEmptyList = true;
            builder._isExitAfterEOF = false;
            return builder;
        }

        public static Builder newClient(ReadableByteChannel in,
                                        WritableByteChannel out,
                                        Iterable<Path> sourceFiles,
                                        byte[] checksumSeed)
        {
            Builder builder = new Builder(in, out, sourceFiles, checksumSeed);
            builder._isReceiveFilterRules = false;
            builder._isSendStatistics = false;
            builder._isExitEarlyIfEmptyList = false;
            builder._isExitAfterEOF = true;
            return builder;

        }

        public Builder fileSelection(FileSelection fileSelection)
        {
            assert fileSelection != null;
            _fileSelection = fileSelection;
            return this;
        }

        public Builder isPreserveUser(boolean isPreserveUser)
        {
            _isPreserveUser = isPreserveUser;
            return this;
        }

        public Builder charset(Charset charset)
        {
            assert charset != null;
            _charset = charset;
            return this;
        }

        public Builder isExitEarlyIfEmptyList(boolean isExitEarlyIfEmptyList)
        {
            _isExitEarlyIfEmptyList = isExitEarlyIfEmptyList;
            return this;
        }

        public Builder isInterruptible(boolean isInterruptible)
        {
            _isInterruptible = isInterruptible;
            return this;
        }

        public Builder isSafeFileList(boolean isSafeFileList)
        {
            _isSafeFileList = isSafeFileList;
            return this;
        }

        public Sender build()
        {
            return new Sender(this);
        }
    }

    private static final Logger _log =
        Logger.getLogger(Sender.class.getName());
    private static final int INPUT_CHANNEL_BUF_SIZE = 8 * 1024;
    private static final int OUTPUT_CHANNEL_BUF_SIZE = 8 * 1024;
    private static final int PARTIAL_FILE_LIST_SIZE = 1024;
    private static final int CHUNK_SIZE = 8 * 1024;

    private final AutoFlushableRsyncDuplexChannel _duplexChannel;
    private final BitSet _transferred = new BitSet();
    private final boolean _isExitAfterEOF;
    private final boolean _isExitEarlyIfEmptyList;
    private final boolean _isInterruptible;
    private final boolean _isPreserveUser;
    private final boolean _isReceiveFilterRules;
    private final boolean _isSafeFileList;
    private final boolean _isSendStatistics;
    private final byte[] _checksumSeed;
    private final FileInfoCache _fileInfoCache = new FileInfoCache();
    private final FileSelection _fileSelection;
    private final Iterable<Path> _sourceFiles;
    private final Set<User> _transferredUserNames = new LinkedHashSet<>();
    private final Statistics _stats = new Statistics();
    private final TextDecoder _characterDecoder;
    private final TextEncoder _characterEncoder;

    private int _curSegmentIndex;
    private int _ioError;

    private Sender(Builder builder)
    {
        _duplexChannel = new AutoFlushableRsyncDuplexChannel(
                             new RsyncInChannel(builder._in,
                                                this,
                                                INPUT_CHANNEL_BUF_SIZE),
                             new RsyncOutChannel(builder._out,
                                                 OUTPUT_CHANNEL_BUF_SIZE));
        _isExitAfterEOF = builder._isExitAfterEOF;
        _isExitEarlyIfEmptyList = builder._isExitEarlyIfEmptyList;
        _isInterruptible = builder._isInterruptible;
        _isPreserveUser = builder._isPreserveUser;
        _isReceiveFilterRules = builder._isReceiveFilterRules;
        _isSafeFileList = builder._isSafeFileList;
        _isSendStatistics = builder._isSendStatistics;
        _checksumSeed = builder._checksumSeed;
        _fileSelection = builder._fileSelection;
        _sourceFiles = builder._sourceFiles;
        _characterDecoder = TextDecoder.newStrict(builder._charset);
        _characterEncoder = TextEncoder.newStrict(builder._charset);
    }

    @Override
    public boolean isInterruptible()
    {
        return _isInterruptible;
    }

    @Override
    public void closeChannel() throws ChannelException
    {
        _duplexChannel.close();
    }

    @Override
    public Boolean call() throws ChannelException, InterruptedException
    {
        Filelist fileList =
                new Filelist(_fileSelection == FileSelection.RECURSE, false);
        try {
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("Sender.transfer:");
            }
            if (_isReceiveFilterRules) {
                String rules = receiveFilterRules();
                if (rules.length() > 0) {
                    throw new RsyncProtocolException(String.format(
                            "Received a list of filter rules of length %d " +
                            "from peer, this is not yet supported (%s)",
                            rules.length(), rules));
                }
            }

            long t1 = System.currentTimeMillis();

            StatusResult<List<FileInfo>> expandResult =
                    initialExpand(_sourceFiles);
            boolean isInitialListOK = expandResult.isOK();
            Filelist.SegmentBuilder builder = new Filelist.SegmentBuilder(null);
            builder.addAll(expandResult.value());
            Filelist.Segment initialSegment = fileList.newSegment(builder);

            long numBytesWritten = _duplexChannel.numBytesWritten();
            for (FileInfo f : initialSegment.files()) {
                sendFileMetaData(f);
            }
            long t2 = System.currentTimeMillis();
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("expanded segment: " + initialSegment.toString());
            }
            if (isInitialListOK) {
                sendSegmentDone();
            } else {
                sendFileListErrorNotification();
            }
            long t3 = System.currentTimeMillis();

            if (_isPreserveUser && _fileSelection != FileSelection.RECURSE) {
                sendUserList();
            }

            _stats.setFileListBuildTime(Math.max(1, t2 - t1));
            _stats.setFileListTransferTime(Math.max(0, t3 - t2));
            long segmentSize = _duplexChannel.numBytesWritten() -
                               numBytesWritten;
            _stats.setTotalFileListSize(_stats.totalFileListSize() +
                                        segmentSize);
            if (!_isSafeFileList && !isInitialListOK) {
                sendIntMessage(MessageCode.IO_ERROR, IoError.GENERAL);
            }

            if (initialSegment.isFinished() && _isExitEarlyIfEmptyList) {
                if (_log.isLoggable(Level.FINE)) {
                    _log.fine("empty file list - exiting early");
                }
                _duplexChannel.flush();
                if (_isExitAfterEOF) {
                    readAllMessagesUntilEOF();
                }
                return isInitialListOK;
            }

            int ioError = sendFiles(fileList);
            if (ioError != 0) {
                sendIntMessage(MessageCode.IO_ERROR, ioError);
            }
            _duplexChannel.encodeIndex(Filelist.DONE);

            // we update the statistics in finally clause to guarantee that the
            // statistics are updated even if there's an error
            if (_isSendStatistics) {
                _stats.setTotalFileSize(fileList.totalFileSize());
                _stats.setTotalRead(_duplexChannel.numBytesRead());
                _stats.setTotalWritten(_duplexChannel.numBytesWritten());
                _stats.setNumFiles(fileList.numFiles());
                sendStatistics(_stats);
            }

            int index = _duplexChannel.decodeIndex();
            if (index != Filelist.DONE) {
                throw new RsyncProtocolException(
                    String.format("Invalid packet at end of run (%d)", index));
            }
            if (_isExitAfterEOF) {
                readAllMessagesUntilEOF();
            }
            return isInitialListOK && (ioError | _ioError) == 0;
        } catch (RuntimeInterruptException e) {
            throw new InterruptedException();
        } finally {
            _stats.setTotalFileSize(fileList.totalFileSize());
            _stats.setTotalRead(_duplexChannel.numBytesRead());
            _stats.setTotalWritten(_duplexChannel.numBytesWritten());
            _stats.setNumFiles(fileList.numFiles());
        }
    }

    private void sendUserId(int uid) throws ChannelException
    {
        if (_log.isLoggable(Level.FINER)) {
            _log.finer("sending user id " + uid);
        }
        sendEncodedInt(uid);
    }

    private void sendUserName(String name) throws ChannelException
    {
        if (_log.isLoggable(Level.FINER)) {
            _log.finer("sending user name " + name);
        }
        ByteBuffer buf = ByteBuffer.wrap(_characterEncoder.encode(name));
        // unlikely scenario, we could also recover from this (by truncating or
        // falling back to nobody)
        if (buf.remaining() > 0xFF) {
            throw new IllegalStateException(String.format(
                "encoded length of user name %s is %d, which is larger than " +
                "what fits in a byte (255)", name, buf.remaining()));
        }
        _duplexChannel.putByte((byte) buf.remaining());
        _duplexChannel.put(buf);
    }

    private void sendUserList() throws ChannelException
    {
        for (User user : _transferredUserNames) {
            assert user.uid() != User.root().uid();
            sendUserId(user.uid());
            sendUserName(user.name());
        }
        sendEncodedInt(0);
    }

    /**
     * @throws RsyncProtocolException if peer sends a message we cannot decode
     */
    @Override
    public void handleMessage(Message message)
    {
        switch (message.header().messageType()) {
        case IO_ERROR:
            _ioError |= message.payload().getInt();
            break;
        case INFO:
        case ERROR:
        case ERROR_XFER:
        case WARNING:
        case LOG:
            // throws TextConversionException
            printMessage(message);
            break;
        default:
            throw new RuntimeException(
                "TODO: (not yet implemented) missing case statement for " +
                message);
        }
    }

    /**
     * @throws RsyncProtocolException if peer sends a message we cannot decode
     */
    private void printMessage(Message message)
    {
        assert message.isText();
        try {
            MessageCode msgType = message.header().messageType();
            if (msgType.equals(MessageCode.ERROR_XFER)) {
                _ioError |= IoError.TRANSFER;
            }
            if (_log.isLoggable(message.logLevelOrNull())) {
                // throws TextConversionException
                String text = _characterDecoder.decode(message.payload());
                // Receiver here means the opposite of Sender - not the process
                // (which actually is the Generator)
                _log.log(message.logLevelOrNull(),
                         String.format("<RECEIVER> %s: %s",
                                       msgType, Text.stripLast(text)));
            }
        } catch (TextConversionException e) {
            if (_log.isLoggable(Level.SEVERE)) {
                _log.severe(String.format(
                    "Peer sent a message but we failed to convert all " +
                    "characters in message. %s (%s)", e, message.toString()));
            }
            throw new RsyncProtocolException(e);
        }
    }

    public Statistics statistics()
    {
        return _stats;
    }

    /**
     * @throws RsyncProtocolException if failing to decode the filter rules
     */
    private String receiveFilterRules() throws ChannelException
    {
        try {
            int numBytesToRead = _duplexChannel.getInt();
            ByteBuffer buf = _duplexChannel.get(numBytesToRead);
            String filterRules = _characterDecoder.decode(buf);
            return filterRules;
        } catch (TextConversionException e) {
            throw new RsyncProtocolException(e);
        }
    }

    private int sendFiles(Filelist fileList)
        throws ChannelException
    {
        boolean sentEOF = false;
        ConnectionState connectionState = new ConnectionState();
        int ioError = 0;
        Filelist.Segment segment = fileList.firstSegment();
        int numFilesInTransit = segment.files().size();

        while (connectionState.isTransfer()) {
            // We must send a new segment when we have at least one segment
            // active to avoid deadlocking when talking to rsync
            if (fileList.isExpandable() &&
                (fileList.expandedSegments() == 1 ||
                 numFilesInTransit < PARTIAL_FILE_LIST_SIZE / 2))
            {
                if (_log.isLoggable(Level.FINE)) {
                    _log.fine(String.format(
                            "expanding file list. In transit: %d files, " +
                            "%d segments", numFilesInTransit,
                            fileList.expandedSegments()));
                }
                int lim = Math.max(1, PARTIAL_FILE_LIST_SIZE -
                                      numFilesInTransit);
                StatusResult<Integer> res = expandAndSendSegments(fileList,
                                                                  lim);
                numFilesInTransit += res.value();
                if (!res.isOK()) {
                    if (_log.isLoggable(Level.WARNING)) {
                        _log.warning("got I/O error during file list " +
                                     "expansion, notifying peer");
                    }
                    ioError |= IoError.GENERAL;
                    sendIntMessage(MessageCode.IO_ERROR, ioError);
                }
            }
            if (_fileSelection == FileSelection.RECURSE &&
                !fileList.isExpandable() && !sentEOF)
            {
                if (_log.isLoggable(Level.FINE)) {
                    _log.fine("sending file list EOF");
                }
                _duplexChannel.encodeIndex(Filelist.EOF);
                sentEOF = true;
            }

            if (_log.isLoggable(Level.FINE)) {
                _log.fine(String.format(
                    "num bytes buffered: %d, num bytes available to read: %d",
                    _duplexChannel.numBytesBuffered(),
                    _duplexChannel.numBytesAvailable()));
            }

            final int index = _duplexChannel.decodeIndex();
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("Received index " + index);
            }

            if (index == Filelist.DONE) {
                if (_fileSelection == FileSelection.RECURSE &&
                    !fileList.isEmpty())
                {
                    // we're unable to delete the segment opportunistically
                    // because we're not being notified about all files that
                    // the receiver is finished with
                    Filelist.Segment removed = fileList.deleteFirstSegment();
                    if (_log.isLoggable(Level.FINE)) {
                        _log.fine("Deleting segment: " + removed);
//                        if (_log.isLoggable(Level.FINEST)) {
//                            _log.finest(removed.filesToString());
//                        }
                    }
                    if (!fileList.isEmpty()) {
                        _duplexChannel.encodeIndex(Filelist.DONE);
                    }
                    numFilesInTransit -= removed.files().size();
                }
                if (_fileSelection != FileSelection.RECURSE ||
                    fileList.isEmpty())
                {
                    connectionState.doTearDownStep();
                    if (connectionState.isTransfer()) {
                        _duplexChannel.encodeIndex(Filelist.DONE);
                    }
                }
            } else if (index >= 0) {
                char iFlags = _duplexChannel.getChar();
                if (!Item.isValidItem(iFlags)) {
                    throw new IllegalStateException(String.format(
                        "got flags %s - not supported",
                        Integer.toBinaryString((int) iFlags)));
                }
                if ((iFlags & Item.TRANSFER) == 0) {
                    if (segment == null ||
                        segment.getFileWithIndexOrNull(index) == null) {
                        segment = fileList.getSegmentWith(index);
                    }
                    assert segment != null;
                    if (_fileSelection == FileSelection.RECURSE &&
                        segment == null)
                    {
                        throw new RsyncProtocolException(String.format(
                            "Received invalid file/directory index %d from " +
                            "peer",
                            index));
                    }
                    if (index != segment.directoryIndex()) {
                        FileInfo removed = segment.remove(index);
                        if (_log.isLoggable(Level.FINE)) {
                            _log.fine(String.format("Deleting file/dir %s %d",
                                                    removed, index));
                        }
                        numFilesInTransit--;
                    }
                    sendIndexAndIflags(index, iFlags);
                } else if (!connectionState.isTearingDown()) {
                    FileInfo fileInfo = null;
                    if (segment != null) {
                        fileInfo = segment.getFileWithIndexOrNull(index);
                    }
                    if (fileInfo == null) {
                        segment = fileList.getSegmentWith(index);
                    }
                    if (segment == null) {
                        throw new RsyncProtocolException(String.format(
                            "Received invalid file index %d from peer",
                            index));
                    }
                    if (_log.isLoggable(Level.FINE)) {
                        _log.fine("caching segment: " + segment);
//                        if (_log.isLoggable(Level.FINEST)) {
//                            _log.finest(segment.filesToString());
//                        }
                    }

                    fileInfo = segment.getFileWithIndexOrNull(index);
                    if (fileInfo == null ||
                        !fileInfo.attrs().isRegularFile()) {
                        throw new RsyncProtocolException(String.format(
                            "index %d is not a regular file (%s)",
                            index, fileInfo));
                    }

                    if (_log.isLoggable(Level.FINE)) {
                        if (isTransferred(index)) {
                            _log.fine("Re-sending " + fileInfo.pathOrNull());
                        } else {
                            _log.fine("sending " + fileInfo);
                        }
                    }

                    Checksum.Header header = receiveChecksumHeader();
                    if (_log.isLoggable(Level.FINE)) {
                        _log.fine("received peer checksum " + header);
                    }
                    Checksum checksum = receiveChecksumsFor(header);

                    boolean isNew = header.blockLength() == 0;
                    int blockSize = isNew ? FileView.DEFAULT_BLOCK_SIZE
                                          : header.blockLength();
                    int blockFactor = isNew ? 1 : 10;
                    long fileSize = fileInfo.attrs().size();

                    byte[] fileMD5sum = null;
                    try (FileView fv = new FileView(fileInfo.pathOrNull(),
                                                    fileInfo.attrs().size(),
                                                    blockSize,
                                                    blockSize * blockFactor)) {

                        sendIndexAndIflags(index, iFlags);
                        sendChecksumHeader(header);

                        if (isNew) {
                            fileMD5sum = skipMatchSendData(fv, fileSize);
                        } else {
                            fileMD5sum = sendMatchesAndData(fv, checksum,
                                                            fileSize);
                        }
                    } catch (FileViewOpenFailed e) { // on FileView.open()
                        if (_log.isLoggable(Level.WARNING)) {
                            _log.warning(String.format(
                                "Error: cannot open %s: %s",
                                fileInfo, e.getMessage()));
                        }
                        if (e instanceof FileViewNotFound) {
                            ioError |= IoError.VANISHED;
                        } else {
                            ioError |= IoError.GENERAL;
                        }

                        FileInfo removed = segment.remove(index);
                        if (_log.isLoggable(Level.FINE)) {
                            _log.fine(String.format("Purging %s index=%d",
                                                    removed, index));
                        }
                        sendIntMessage(MessageCode.NO_SEND, index);
                        continue;
                    } catch (FileViewReadError e) {  // on FileView.close()
                        if (_log.isLoggable(Level.WARNING)) {
                            _log.warning(String.format(
                                "Error: general I/O error on %s (ignored and" +
                                " skipped): %s", fileInfo, e.getMessage()));
                        }
                        // is only null for FileViewOpenFailed - not
                        // FileViewReadError which is caused by FileView.close()
                        fileMD5sum[0]++;
                    }

                    if (_log.isLoggable(Level.FINE)) {
                        _log.finer(String.format(
                            "sending checksum for %s: %s",
                            fileInfo.pathOrNull(), Text.bytesToString(fileMD5sum)));
                    }
                    _duplexChannel.put(fileMD5sum, 0, fileMD5sum.length);
                    setIsTransferred(index);

                    if (_log.isLoggable(Level.FINE)) {
                        _log.fine(String.format("sent %s (%d bytes)",
                                                fileInfo.pathOrNull(), fileSize));
                    }

                    _stats.setNumTransferredFiles(_stats.numTransferredFiles() +
                                                  1);
                    _stats.setTotalTransferredSize(_stats.totalTransferredSize()
                                                   + fileInfo.attrs().size());
                } else {
                    throw new RsyncProtocolException(String.format(
                        "Error: received index in wrong phase (%s)",
                        connectionState));
                }
            } else {
                throw new RsyncProtocolException(
                    String.format("Error: received invalid index %d from peer",
                                  index));
            }
        }

        if (_log.isLoggable(Level.FINE)) {
            _log.fine("finished sending files");
        }

        return ioError;
    }

    // NOTE: doesn't do any check of the validity of files or normalization -
    // it's up to the caller to do so, e.g. ServerSessionConfig.parseArguments
    private StatusResult<List<FileInfo>>
    initialExpand(Iterable<Path> files)
    {
        boolean isOK = true;
        List<FileInfo> fileset = new LinkedList<>();

        for (Path p : files) {
            try {
                if (_log.isLoggable(Level.FINE)) {
                    _log.fine("expanding " + p);
                }

                RsyncFileAttributes attrs = RsyncFileAttributes.stat(p);
                // throws TextConversionException
                byte[] nameBytes =
                        _characterEncoder.encode(p.getFileName().toString());
                // throws IllegalArgumentException but that cannot happen
                FileInfo fileInfo = new FileInfo(p, p.getFileName(), nameBytes,
                                                 attrs);
                if (_fileSelection == FileSelection.EXACT &&
                    fileInfo.attrs().isDirectory())
                {
                    if (_log.isLoggable(Level.INFO)) {
                        _log.info("skipping directory " + fileInfo);
                    }
                } else {
                    if (_log.isLoggable(Level.FINE)) {
                        _log.fine(String.format("adding %s to segment",
                                                fileInfo));
                    }
                    fileset.add(fileInfo);
                    if (fileInfo.isDotDir()) {
                        if (_log.isLoggable(Level.FINE)) {
                            _log.fine("expanding dot dir " + fileInfo);
                        }
                        StatusResult<List<FileInfo>> expandResult =
                                expand(fileInfo);
                        isOK = isOK && expandResult.isOK();
                        for (FileInfo f2 : expandResult.value()) {
                            fileset.add(f2);
                        }
                        _curSegmentIndex++;
                    }
                }
            } catch (IOException e) {
                if (_log.isLoggable(Level.WARNING)) {
                    _log.warning(String.format(
                        "Failed to add %s to initial file list: %s",
                        p, e.getMessage()));
                }
                isOK = false;
            } catch (TextConversionException e) {
                if (_log.isLoggable(Level.WARNING)) {
                    _log.warning(String.format("Failed to encode %s using %s",
                                               p, _characterEncoder.charset()));
                }
                isOK = false;
            }
        }
        return new StatusResult<List<FileInfo>>(isOK, fileset);
    }

    private StatusResult<List<FileInfo>> expand(FileInfo directory)
    {
        assert directory != null;

        List<FileInfo> fileset = new ArrayList<>();
        boolean isOK = true;
        // throws RuntimeException if unable to get local path prefix of
        // directory, but that should never happen
        final Path localPart = getLocalPathOf(directory);

        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(directory.pathOrNull())) {

            for (Path entry : stream) {
                if (!PathOps.isPathPreservable(entry.getFileName())) {
                    if (_log.isLoggable(Level.WARNING)) {
                        _log.warning(String.format(
                            "Skipping %s - unable to preserve file name",
                            entry.getFileName()));
                    }
                    isOK = false;
                    continue;
                }

                RsyncFileAttributes attrs;
                try {
                    attrs = RsyncFileAttributes.stat(entry);
                } catch (IOException e) {
                    if (_log.isLoggable(Level.WARNING)) {
                        _log.warning(String.format("Failed to stat %s: %s",
                                                   entry, e.getMessage()));
                    }
                    isOK = false;
                    continue;
                }

                Path relativePath = localPart.relativize(entry);
                String relativePathName =
                    Text.withSlashAsPathSepator(relativePath.toString());
                byte[] pathNameBytes =
                    _characterEncoder.encodeOrNull(relativePathName);
                if (pathNameBytes != null) {
                    // throws IllegalArgumentException but that cannot happen
                    FileInfo f = new FileInfo(entry, relativePath,
                                              pathNameBytes, attrs);
                    fileset.add(f);
                } else {
                    if (_log.isLoggable(Level.WARNING)) {
                        _log.warning(String.format(
                            "Failed to encode %s using %s",
                            relativePathName, _characterEncoder.charset()));
                    }
                    isOK = false;
                }
            }
        } catch (IOException e) {
            if (_log.isLoggable(Level.WARNING)) {
                _log.warning(String.format("Got I/O error during expansion " +
                                           "of %s: %s",
                                           directory.pathOrNull(), e.getMessage()));
            }
            isOK = false;
        }
        return new StatusResult<List<FileInfo>>(isOK, fileset);
    }

    private StatusResult<Integer> expandAndSendSegments(Filelist fileList,
                                                        int limit)
        throws ChannelException
    {
        boolean isOK = true;
        int numFilesSent = 0;
        int numSegmentsSent = 0;
        long numBytesWritten = _duplexChannel.numBytesWritten();

        if (_log.isLoggable(Level.FINE)) {
            _log.fine(String.format("expanding segments until at least %d " +
                                    "files have been sent", limit));
        }

        while (fileList.isExpandable() && numFilesSent < limit) {

            if (_log.isLoggable(Level.FINE)) {
                _log.fine(String.format("sending segment index %d (as %d)",
                                        _curSegmentIndex,
                                        Filelist.OFFSET - _curSegmentIndex));
            }

            assert _curSegmentIndex >= 0;
            FileInfo directory =
                fileList.getStubDirectoryOrNull(_curSegmentIndex);
            assert directory != null;
            _duplexChannel.encodeIndex(Filelist.OFFSET - _curSegmentIndex);

            StatusResult<List<FileInfo>> expandResult = expand(directory);
            boolean isExpandOK = expandResult.isOK();
            if (!isExpandOK && _log.isLoggable(Level.WARNING)) {
                _log.warning("initial file list expansion returned an error");
            }

            Filelist.SegmentBuilder builder =
                new Filelist.SegmentBuilder(directory);
            builder.addAll(expandResult.value());
            Filelist.Segment segment = fileList.newSegment(builder);

            if (_log.isLoggable(Level.FINE)) {
                _log.fine(String.format("expanded segment with segment index" +
                                        " %d", _curSegmentIndex));
                if (_log.isLoggable(Level.FINER)) {
                    _log.finer(segment.toString());
                }
            }

            for (FileInfo fileInfo : segment.files()) {
                sendFileMetaData(fileInfo);
                numFilesSent++;
            }

            if (isExpandOK) {
                sendSegmentDone();
            } else {
                // NOTE: once an error happens for native it will send an error
                // for each file list segment for the same loop block - we don't
                isOK = false;
                sendFileListErrorNotification();
            }
            _curSegmentIndex++;
            numSegmentsSent++;
        }

        long segmentSize = _duplexChannel.numBytesWritten() - numBytesWritten;
        _stats.setTotalFileListSize(_stats.totalFileListSize() + segmentSize);

        if (_log.isLoggable(Level.FINE)) {
            _log.fine(String.format("sent meta data for %d segments and %d " +
                                    "files", numSegmentsSent, numFilesSent));
        }

        return new StatusResult<Integer>(isOK, numFilesSent);
    }

    // flist.c:send_file_entry
    private void sendFileMetaData(FileInfo fileInfo) throws ChannelException
    {
        if (_log.isLoggable(Level.FINE)) {
            _log.fine("sending meta data for " + fileInfo.pathOrNull());
        }

        boolean preserveLinks = false;
        char xflags = 0;

        RsyncFileAttributes attrs = fileInfo.attrs();
        if (attrs.isDirectory()) {
            xflags = 1;
        }

        int mode = attrs.mode();
        if (mode == _fileInfoCache.getPrevMode()) {
            xflags |= TransmitFlags.SAME_MODE;
        } else {
            _fileInfoCache.setPrevMode(mode);
        }

        User user = fileInfo.attrs().user();
        if (_isPreserveUser &&
            !user.equals(_fileInfoCache.getPrevUserOrNull()))
        {
            _fileInfoCache.setPrevUser(user);
            if (!user.equals(User.root())) {
                if (_fileSelection == FileSelection.RECURSE &&
                    !_transferredUserNames.contains(user))
                {
                    xflags |= TransmitFlags.USER_NAME_FOLLOWS;
                } // else send in batch later
                _transferredUserNames.add(user);
            }
        } else {
            xflags |= TransmitFlags.SAME_UID;
        }

        xflags |= TransmitFlags.SAME_GID;

        long lastModified = attrs.lastModifiedTime();
        if (lastModified == _fileInfoCache.getPrevLastModified()) {
            xflags |= TransmitFlags.SAME_TIME;
        } else {
            _fileInfoCache.setPrevLastModified(lastModified);
        }

        byte[] fileNameBytes = fileInfo.pathNameBytes();
        int commonPrefixLength =
            lengthOfLargestCommonPrefix(_fileInfoCache.getPrevFileNameBytes(),
                                        fileNameBytes);
        byte[] prefixBytes = Arrays.copyOfRange(fileNameBytes,
                                                0,
                                                commonPrefixLength);
        byte[] suffixBytes = Arrays.copyOfRange(fileNameBytes,
                                                commonPrefixLength,
                                                fileNameBytes.length);
        int numSuffixBytes = suffixBytes.length;
        int numPrefixBytes = Math.min(prefixBytes.length, 255);
        if (numPrefixBytes > 0) {
            xflags |= TransmitFlags.SAME_NAME;
        }
        if (numSuffixBytes > 255) {
            xflags |= TransmitFlags.LONG_NAME;
        }
        _fileInfoCache.setPrevFileNameBytes(fileNameBytes);

        if (xflags == 0 && !attrs.isDirectory()) {
            xflags |= TransmitFlags.TOP_DIR;
        }
        if (xflags == 0 || (xflags & 0xFF00) != 0) {
            xflags |= TransmitFlags.EXTENDED_FLAGS;
            _duplexChannel.putChar(xflags);
        } else {
            _duplexChannel.putByte((byte) xflags);
        }
        if (_log.isLoggable(Level.FINER)) {
            _log.finer("sent flags " + Integer.toBinaryString(xflags));
        }

        if ((xflags & TransmitFlags.SAME_NAME) != 0) {
            _duplexChannel.putByte((byte) numPrefixBytes);
        }

        if ((xflags & TransmitFlags.LONG_NAME) != 0) {
            sendEncodedInt(numSuffixBytes);
        } else {
            _duplexChannel.putByte((byte) numSuffixBytes);
        }
        _duplexChannel.put(ByteBuffer.wrap(suffixBytes));

        sendEncodedLong(attrs.size(), 3);

        if ((xflags & TransmitFlags.SAME_TIME) == 0) {
            sendEncodedLong(lastModified, 4);
        }

        if ((xflags & TransmitFlags.SAME_MODE) == 0) {
            _duplexChannel.putInt(mode);
        }

        if (_isPreserveUser && ((xflags & TransmitFlags.SAME_UID) == 0)) {
            sendUserId(user.uid());
            if ((xflags & TransmitFlags.USER_NAME_FOLLOWS) != 0) {
                sendUserName(user.name());
            }
        }

        // TODO: assert fileName is equal to symbolic link name in native
        if (preserveLinks && attrs.isSymbolicLink()) {
            sendEncodedInt(fileNameBytes.length);
            _duplexChannel.put(ByteBuffer.wrap(fileNameBytes));
        }
    }

    private void sendSegmentDone() throws ChannelException
    {
        if (_log.isLoggable(Level.FINE)) {
            _log.fine("sending segment done");
        }
        _duplexChannel.putByte((byte) 0);
    }

    private void sendFileListErrorNotification() throws ChannelException
    {
        if (_log.isLoggable(Level.FINE)) {
            _log.fine("sending file list error notification to peer");
        }
        if (_isSafeFileList) {
            _duplexChannel.putChar(
                (char) (0xFFFF & (TransmitFlags.EXTENDED_FLAGS |
                                  TransmitFlags.IO_ERROR_ENDLIST)));
            sendEncodedInt(IoError.GENERAL);
        } else {
            _duplexChannel.putByte((byte) 0);
        }
    }

    private void sendChecksumHeader(Checksum.Header header)
        throws ChannelException
    {
        Connection.sendChecksumHeader(_duplexChannel, header);
    }

    private Checksum.Header receiveChecksumHeader() throws ChannelException
    {
        return Connection.receiveChecksumHeader(_duplexChannel);
    }

    private static int lengthOfLargestCommonPrefix(byte[] left, byte[] right)
    {
        int index = 0;
        while (index < left.length &&
               index < right.length &&
               left[index] == right[index]) {
            index++;
        }
        return index;
    }

    private void sendIndexAndIflags(int index, char iFlags)
        throws ChannelException
    {
        if (!Item.isValidItem(iFlags)) {
            throw new IllegalStateException(String.format(
                    "got flags %s - not supported",
                    Integer.toBinaryString((int) iFlags)));
        }
        _duplexChannel.encodeIndex(index);
        _duplexChannel.putChar(iFlags);
    }

    private Checksum receiveChecksumsFor(Checksum.Header header)
        throws ChannelException
    {
        Checksum checksum = new Checksum(header);
        for (int i = 0; i < header.chunkCount(); i++) {
            int rolling = _duplexChannel.getInt();
            byte[] md5sum = new byte[header.digestLength()];
            _duplexChannel.get(md5sum, 0, md5sum.length);
            checksum.addChunkInformation(rolling, md5sum);
        }
        return checksum;
    }

    private byte[] skipMatchSendData(FileView view, long fileSize)
        throws ChannelException
    {
        MessageDigest fileDigest = MD5.newInstance();
        long bytesSent = 0;
        while (view.windowLength() > 0) {
            sendDataFrom(view.array(), view.startOffset(), view.windowLength());
            bytesSent += view.windowLength();
            fileDigest.update(view.array(), view.startOffset(),
                              view.windowLength());
            view.slide(view.windowLength());
        }
        _stats.setTotalLiteralSize(_stats.totalLiteralSize() + fileSize);
        _duplexChannel.putInt(0);
        assert bytesSent == fileSize;
        return fileDigest.digest();
    }

    private byte[] sendMatchesAndData(FileView fv,
                                      Checksum peerChecksum,
                                      long fileSize)
        throws ChannelException
    {
        assert fv != null;
        assert peerChecksum != null;
        assert peerChecksum.header().blockLength() > 0;
        assert fileSize > 0;

        MessageDigest fileDigest = MD5.newInstance();
        MessageDigest chunkDigest = MD5.newInstance();

        int rolling = Rolling.compute(fv.array(), fv.startOffset(),
                                      fv.windowLength());
        int preferredIndex = 0;
        long sizeLiteral = 0;
        long sizeMatch = 0;
        byte[] localChunkMd5sum = null;
        fv.setMarkRelativeToStart(0);

        while (fv.windowLength() >= peerChecksum.header().smallestChunkSize()) {

            if (_log.isLoggable(Level.FINEST)) {
                _log.finest(fv.toString());
            }

            for (Checksum.Chunk chunk : peerChecksum.getCandidateChunks(
                                                            rolling,
                                                            fv.windowLength(),
                                                            preferredIndex)) {

                if (localChunkMd5sum == null) {
                    chunkDigest.update(fv.array(),
                                       fv.startOffset(),
                                       fv.windowLength());
                    chunkDigest.update(_checksumSeed);
                    localChunkMd5sum = Arrays.copyOf(
                                                chunkDigest.digest(),
                                                chunk.md5Checksum().length);
                }

                if (Arrays.equals(localChunkMd5sum, chunk.md5Checksum())) {
                    if (_log.isLoggable(Level.FINER)) {
                        _log.finer(String.format(
                            "match %s == %s %s",
                            MD5.md5DigestToString(localChunkMd5sum),
                            MD5.md5DigestToString(chunk.md5Checksum()),
                            fv));
                    }
                    sizeMatch += fv.windowLength();
                    sendDataFrom(fv.array(), fv.markOffset(),
                                 fv.numBytesMarked());
                    sizeLiteral += fv.numBytesMarked();
                    fileDigest.update(fv.array(),
                                      fv.markOffset(),
                                      fv.totalBytes());

                    _duplexChannel.putInt(- (chunk.chunkIndex() + 1));
                    preferredIndex = chunk.chunkIndex() + 1;
                    // we have sent all literal data until start of this
                    // chunk which in turn is matching peer's checksum,
                    // reset cursor:
                    fv.setMarkRelativeToStart(fv.windowLength());
                    // slide start to 1 byte left of mark offset,
                    // will be subtracted immediately after break of loop
                    fv.slide(fv.windowLength() - 1);
                    // TODO: optimize away an unnecessary expensive compact
                    // operation here while we only have 1 byte to compact,
                    // before reading in more data (if we're at the last block)
                    rolling = Rolling.compute(fv.array(),
                                              fv.startOffset(),
                                              fv.windowLength());
                    localChunkMd5sum = null;
                    break;
                }
            }

            rolling = Rolling.subtract(rolling,
                                       fv.windowLength(),
                                       fv.valueAt(fv.startOffset()));

            if (fv.isFull()) {
                if (_log.isLoggable(Level.FINER)) {
                    _log.finer("view is full " + fv);
                }
                sendDataFrom(fv.array(), fv.firstOffset(), fv.totalBytes());
                sizeLiteral += fv.totalBytes();
                fileDigest.update(fv.array(), fv.firstOffset(),
                                  fv.totalBytes());
                fv.setMarkRelativeToStart(fv.windowLength());
                fv.slide(fv.windowLength());
            } else {
                fv.slide(1);
            }

            // i.e. not at the end of the file
            if (fv.windowLength() == peerChecksum.header().blockLength()) {
                rolling = Rolling.add(rolling, fv.valueAt(fv.endOffset()));
            }
        }

        sendDataFrom(fv.array(), fv.firstOffset(), fv.totalBytes());
        sizeLiteral += fv.totalBytes();
        fileDigest.update(fv.array(), fv.firstOffset(), fv.totalBytes());
        _duplexChannel.putInt(0);

        if (_log.isLoggable(Level.FINE)) {
            _log.fine(String.format("%d%% match: matched %d bytes, sent %d" +
                                    " bytes (file size %d bytes) %s",
                                    Math.round(100 * ((float) sizeMatch /
                                                      (sizeMatch +
                                                       sizeLiteral))),
                                    sizeMatch, sizeLiteral, fileSize, fv));
        }

        _stats.setTotalLiteralSize(_stats.totalLiteralSize() + sizeLiteral);
        _stats.setTotalMatchedSize(_stats.totalMatchedSize() + sizeMatch);
        assert sizeLiteral + sizeMatch == fileSize;
        return fileDigest.digest();
    }


    private void sendDataFrom(byte[] buf, int startOffset, int length)
        throws ChannelException
    {
        assert buf != null;
        assert startOffset >= 0;
        assert length >= 0;
        assert startOffset + length <= buf.length;

        int endOffset = startOffset + length - 1;
        int currentOffset = startOffset;
        while (currentOffset <= endOffset) {
            int len = Math.min(CHUNK_SIZE, endOffset - currentOffset + 1);
            assert len > 0;
            _duplexChannel.putInt(len);
            _duplexChannel.put(buf, currentOffset, len);
            currentOffset += len;
        }
    }

    private void sendIntMessage(MessageCode code, int value)
        throws ChannelException
    {
        ByteBuffer payload =
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(0, value);
        Message message = new Message(code, payload);
        _duplexChannel.putMessage(message);
    }

    private void sendEncodedInt(int i) throws ChannelException
    {
        sendEncodedLong(i, 1);
    }

    private void sendEncodedLong(long l, int minBytes) throws ChannelException
    {
        ByteBuffer b = IntegerCoder.encodeLong(l, minBytes);
        _duplexChannel.put(b);
    }

    private void sendStatistics(Statistics stats) throws ChannelException
    {
        sendEncodedLong(stats.totalRead(), 3);
        sendEncodedLong(stats.totalWritten(), 3);
        sendEncodedLong(stats.totalFileSize(), 3);
        sendEncodedLong(stats.fileListBuildTime(), 3);
        sendEncodedLong(stats.fileListTransferTime(), 3);
    }

    private Path getLocalPathOf(FileInfo fileInfo)
    {
        String pathName =
                _characterDecoder.decodeOrNull(fileInfo.pathNameBytes());
        if (pathName == null) {
            throw new RuntimeException(String.format(
                "unable to decode path name of %s using %s",
                fileInfo, _characterDecoder.charset()));
        }
        Path relativePath = Paths.get(pathName);
        return PathOps.subtractPath(fileInfo.pathOrNull(), relativePath);
    }

    // FIXME: code duplication with Receiver, move to Connection?
    public void readAllMessagesUntilEOF() throws ChannelException
    {
        try {
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("reading final messages until EOF");
            }
            // dummy read to get any final messages from peer
            byte dummy = _duplexChannel.getByte();
            // we're not expected to get this far, getByte should throw
            // NetworkEOFException
            throw new RsyncProtocolException(String.format(
                    "Peer sent invalid data during connection tear down (%d)",
                    dummy));
        } catch (ChannelEOFException e) {
            // It's OK, we expect EOF without having received any data
        }
    }

    private void setIsTransferred(int index)
    {
        _transferred.set(index);
    }

    private boolean isTransferred(int index)
    {
        return _transferred.get(index);
    }
}
