/**
 * Logback: the reliable, generic, fast and flexible logging framework. Copyright (C) 1999-2015, QOS.ch. All rights
 * reserved.
 *
 * This program and the accompanying materials are dual-licensed under either the terms of the Eclipse Public License
 * v1.0 as published by the Eclipse Foundation
 *
 * or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1 as published by the Free Software Foundation.
 */
package ch.qos.logback.core.rolling;

import static ch.qos.logback.core.CoreConstants.MANUAL_URL_PREFIX;

import java.io.File;
import java.time.Instant;

import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.joran.spi.NoAutoStart;
import ch.qos.logback.core.rolling.helper.ArchiveRemover;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import ch.qos.logback.core.rolling.helper.FileFilterUtil;
import ch.qos.logback.core.rolling.helper.SizeAndTimeBasedArchiveRemover;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;

/**
 * This class implement {@link TimeBasedFileNamingAndTriggeringPolicy}
 * interface extending {@link TimeBasedFileNamingAndTriggeringPolicyBase}. This class is intended to be nested
 * within a {@link SizeAndTimeBasedFileNamingAndTriggeringPolicy} instance.  However, it can also be
 * instantiated directly for testing purposes.
 *
 * <p>{@link SizeAndTimeBasedFNATP} class was renamed as {@link SizeAndTimeBasedFileNamingAndTriggeringPolicy}
 * in version 1.5.8.</p>
 *
 * @author Ceki G&uuml;lc&uuml;
 *
 * @param <E>
 * @since 1.5.8
 */
@NoAutoStart
public class SizeAndTimeBasedFileNamingAndTriggeringPolicy<E> extends TimeBasedFileNamingAndTriggeringPolicyBase<E> {

    enum Usage {
        EMBEDDED, DIRECT
    }

    volatile int currentPeriodsCounter = 0;
    FileSize maxFileSize;

    Duration checkIncrement = null;

    static String MISSING_INT_TOKEN = "Missing integer token, that is %i, in FileNamePattern [";
    static String MISSING_DATE_TOKEN = "Missing date token, that is %d, in FileNamePattern [";

    private final Usage usage;

    //InvocationGate invocationGate = new SimpleInvocationGate();

    public SizeAndTimeBasedFileNamingAndTriggeringPolicy() {
        this(Usage.DIRECT);
    }

    public SizeAndTimeBasedFileNamingAndTriggeringPolicy(Usage usage) {
        this.usage = usage;
    }

    public LengthCounter lengthCounter = new LengthCounterBase();



    @Override
    public void start() {
        // we depend on certain fields having been initialized in super class
        super.start();

        if (usage == Usage.DIRECT) {
            addWarn(CoreConstants.SIZE_AND_TIME_BASED_FNATP_IS_DEPRECATED);
            addWarn(CoreConstants.SIZE_AND_TIME_BASED_FNATP_IS_DEPRECATED_BIS);
            addWarn("For more information see " + MANUAL_URL_PREFIX + "appenders.html#SizeAndTimeBasedRollingPolicy");
        }

        if (!super.isErrorFree())
            return;

        if (maxFileSize == null) {
            addError("maxFileSize property is mandatory.");
            withErrors();
        }

        //if (checkIncrement != null)
        //    invocationGate = new SimpleInvocationGate(checkIncrement);

        if (!validateDateAndIntegerTokens()) {
            withErrors();
            return;
        }

        archiveRemover = createArchiveRemover();
        archiveRemover.setContext(context);

        // we need to get the correct value of currentPeriodsCounter.
        // usually the value is 0, unless the appender or the application
        // is stopped and restarted within the same period
        String regex = tbrp.fileNamePattern.toRegexForFixedDate(dateInCurrentPeriod);
        String stemRegex = FileFilterUtil.afterLastSlash(regex);

        computeCurrentPeriodsHighestCounterValue(stemRegex);

        if (isErrorFree()) {
            started = true;
        }
    }

    private boolean validateDateAndIntegerTokens() {
        boolean inError = false;
        if (tbrp.fileNamePattern.getIntegerTokenConverter() == null) {
            inError = true;
            addError(MISSING_INT_TOKEN + tbrp.fileNamePatternStr + "]");
            addError(CoreConstants.SEE_MISSING_INTEGER_TOKEN);
        }
        if (tbrp.fileNamePattern.getPrimaryDateTokenConverter() == null) {
            inError = true;
            addError(MISSING_DATE_TOKEN + tbrp.fileNamePatternStr + "]");
        }

        return !inError;
    }

    protected ArchiveRemover createArchiveRemover() {
        return new SizeAndTimeBasedArchiveRemover(tbrp.fileNamePattern, rc);
    }

    void computeCurrentPeriodsHighestCounterValue(final String stemRegex) {
        File file = new File(getCurrentPeriodsFileNameWithoutCompressionSuffix());
        File parentDir = file.getParentFile();

        File[] matchingFileArray = FileFilterUtil.filesInFolderMatchingStemRegex(parentDir, stemRegex);

        if (matchingFileArray == null || matchingFileArray.length == 0) {
            currentPeriodsCounter = 0;
            return;
        }
        currentPeriodsCounter = FileFilterUtil.findHighestCounter(matchingFileArray, stemRegex);

        // if parent raw file property is not null, then the next
        // counter is max found counter+1
        if (tbrp.getParentsRawFileProperty() != null || (tbrp.compressionMode != CompressionMode.NONE)) {
            // TODO test me
            currentPeriodsCounter++;
        }
    }

    @Override
    public boolean isTriggeringEvent(File activeFile, final E event) {

        long currentTime = getCurrentTime();
        long localNextCheck = atomicNextCheck.get();

        // first check for roll-over based on time
        if (currentTime >= localNextCheck) {
            long nextCheckCandidate = computeNextCheck(currentTime);
            atomicNextCheck.set(nextCheckCandidate);
            Instant instantInElapsedPeriod = dateInCurrentPeriod;
            elapsedPeriodsFileName = tbrp.fileNamePatternWithoutCompSuffix.convertMultipleArguments(
                    instantInElapsedPeriod, currentPeriodsCounter);
            currentPeriodsCounter = 0;
            setDateInCurrentPeriod(currentTime);
            lengthCounter.reset();
            return true;
        }

        boolean result = checkSizeBasedTrigger(activeFile, currentTime);
        if(result)
            lengthCounter.reset();
        return result;
    }

    private boolean checkSizeBasedTrigger(File activeFile, long currentTime) {
        // next check for roll-over based on size
        //if (invocationGate.isTooSoon(currentTime)) {
        //    return false;
        //}

        if (activeFile == null) {
            addWarn("activeFile == null");
            return false;
        }
        if (maxFileSize == null) {
            addWarn("maxFileSize = null");
            return false;
        }



        if (lengthCounter.getLength() >= maxFileSize.getSize()) {

            elapsedPeriodsFileName = tbrp.fileNamePatternWithoutCompSuffix.convertMultipleArguments(dateInCurrentPeriod,
                    currentPeriodsCounter);
            currentPeriodsCounter++;

            return true;
        }

        return false;
    }

    public Duration getCheckIncrement() {
        return null;
    }

    public void setCheckIncrement(Duration checkIncrement) {
       addWarn("Since version 1.5.8, 'checkIncrement' property has no effect");
    }

    @Override
    public String getCurrentPeriodsFileNameWithoutCompressionSuffix() {
        return tbrp.fileNamePatternWithoutCompSuffix.convertMultipleArguments(dateInCurrentPeriod,
                currentPeriodsCounter);
    }

    public void setMaxFileSize(FileSize aMaxFileSize) {
        this.maxFileSize = aMaxFileSize;
    }

    @Override
    public LengthCounter getLengthCounter() {
        return lengthCounter;
    }
}
