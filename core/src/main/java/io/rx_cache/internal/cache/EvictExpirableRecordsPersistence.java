/*
 * Copyright 2016 Victor Albertos
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

package io.rx_cache.internal.cache;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.rx_cache.internal.Locale;
import io.rx_cache.internal.Memory;
import io.rx_cache.internal.Persistence;
import io.rx_cache.internal.Record;
import io.rx_cache.internal.encrypt.GetEncryptKey;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

@Singleton
public class EvictExpirableRecordsPersistence extends Action {
    private final Integer maxMgPersistenceCache;
    private final GetEncryptKey getEncryptKey;
    private static final float PERCENTAGE_MEMORY_STORED_TO_START = 0.95f;
    //VisibleForTesting
    public static final float PERCENTAGE_MEMORY_STORED_TO_STOP = 0.7f;
    private final Observable<String> oEvictingTask;
    private boolean couldBeExpirableRecords, isEncrypted;

    @Inject public EvictExpirableRecordsPersistence(Memory memory, Persistence persistence, Integer maxMgPersistenceCache, GetEncryptKey getEncryptKey) {
        super(memory, persistence);
        this.maxMgPersistenceCache = maxMgPersistenceCache;
        this.getEncryptKey = getEncryptKey;
        this.couldBeExpirableRecords = true;
        this.oEvictingTask = oEvictingTask();
    }

    Observable<String> startTaskIfNeeded(boolean isEncrypted) {
        this.isEncrypted = isEncrypted;
        oEvictingTask.subscribe();
        return oEvictingTask;
    }

    private Observable<String> oEvictingTask() {
        return oEvictingTask(getEncryptKey.getKey());
    }

    private Observable<String> oEvictingTask(final String encryptKey) {
        Observable<String> oEvictingTask = Observable.create(new Observable.OnSubscribe<String>() {
            @Override public void call(Subscriber<? super String> subscriber) {
                if (!couldBeExpirableRecords) {
                    subscriber.onNext(Locale.RECORD_CAN_NOT_BE_EVICTED_BECAUSE_NO_ONE_IS_EXPIRABLE);
                    subscriber.onCompleted();
                    return;
                }

                int storedMB = persistence.storedMB();

                if (!reachedPercentageMemoryToStart(storedMB)) {
                    subscriber.onCompleted();
                    return;
                }

                List<String> allKeys = persistence.allKeys();

                float releasedMBSoFar = 0f;
                for (String key : allKeys) {
                    if (reachedPercentageMemoryToStop(storedMB, releasedMBSoFar)) {
                        break;
                    }

                    Record record = persistence.retrieveRecord(key, isEncrypted, encryptKey);
                    if (record == null) continue;
                    if (!record.isExpirable()) continue;

                    persistence.evict(key);
                    subscriber.onNext(key);

                    releasedMBSoFar += record.getSizeOnMb();
                }

                couldBeExpirableRecords = reachedPercentageMemoryToStop(storedMB, releasedMBSoFar);
                subscriber.onCompleted();
            }
        }).subscribeOn((Schedulers.io()))
          .observeOn(Schedulers.io())
          .doOnError(new Action1<Throwable>() {
                    @Override public void call(Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });

        return oEvictingTask.share();
    }

    private boolean reachedPercentageMemoryToStop(int storedMBWhenStarted, float releasedMBSoFar) {
        float currentStoredMB = storedMBWhenStarted - releasedMBSoFar;
        float requiredStoredMBToStop = maxMgPersistenceCache * PERCENTAGE_MEMORY_STORED_TO_STOP;
        return currentStoredMB <= requiredStoredMBToStop;
    }

    private boolean reachedPercentageMemoryToStart(int storedMB) {
        int requiredStoredMBToStart = (int) (maxMgPersistenceCache * PERCENTAGE_MEMORY_STORED_TO_START);
        return storedMB >= requiredStoredMBToStart;
    }
}