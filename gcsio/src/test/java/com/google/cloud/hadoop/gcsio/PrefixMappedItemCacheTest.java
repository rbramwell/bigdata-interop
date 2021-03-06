/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.hadoop.gcsio;

import static com.google.cloud.hadoop.gcsio.PerformanceCachingGoogleCloudStorageTest.assertContainsInAnyOrder;
import static com.google.cloud.hadoop.gcsio.PerformanceCachingGoogleCloudStorageTest.assertEquals;
import static com.google.cloud.hadoop.gcsio.PerformanceCachingGoogleCloudStorageTest.createObjectItemInfo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PrefixMappedItemCacheTest {
  /* Sample bucket names. */
  private static final String BUCKET_A = "alpha";
  private static final String BUCKET_B = "alph";

  /* Sample object names. */
  private static final String PREFIX_A = "bar";
  private static final String PREFIX_AA = "bar/apple";
  private static final String PREFIX_ABA = "bar/berry/foo";
  private static final String PREFIX_B = "baz";

  /* Sample item info. */
  private static final GoogleCloudStorageItemInfo ITEM_A_A =
      createObjectItemInfo(BUCKET_A, PREFIX_A);
  private static final GoogleCloudStorageItemInfo ITEM_A_AA =
      createObjectItemInfo(BUCKET_A, PREFIX_AA);
  private static final GoogleCloudStorageItemInfo ITEM_A_ABA =
      createObjectItemInfo(BUCKET_A, PREFIX_ABA);
  private static final GoogleCloudStorageItemInfo ITEM_A_B =
      createObjectItemInfo(BUCKET_A, PREFIX_B);
  private static final GoogleCloudStorageItemInfo ITEM_B_A =
      createObjectItemInfo(BUCKET_B, PREFIX_A);

  /** Ticker implementation for testing the cache. */
  private TestTicker ticker;
  /** Instance of the cache being tested. */
  private PrefixMappedItemCache cache;

  @Before
  public void setUp() {
    ticker = new TestTicker();

    // Create the cache configuration.
    PrefixMappedItemCache.Config cacheConfig = new PrefixMappedItemCache.Config();
    cacheConfig.setMaxEntryAgeMillis(10);
    cacheConfig.setTicker(ticker);

    cache = new PrefixMappedItemCache(cacheConfig);
  }

  /** Test items can be retrieved normally. */
  @Test
  public void testGetItemNormal() {
    cache.putItem(ITEM_A_A);

    GoogleCloudStorageItemInfo actualItem = cache.getItem(ITEM_A_A.getResourceId());

    assertEquals(actualItem, ITEM_A_A);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A));
  }

  /** Test missing items cannot be retrieved. */
  @Test
  public void testGetItemMissing() {
    GoogleCloudStorageItemInfo actualItem = cache.getItem(ITEM_A_A.getResourceId());

    assertNull(actualItem);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
  }

  /** Test expired items cannot be retrieved. */
  @Test
  public void testGetItemExpired() {
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A));
    cache.putList(BUCKET_B, PREFIX_A, Lists.newArrayList(ITEM_B_A));
    cache.putItem(ITEM_A_ABA);
    ticker.setTimeMillis(11);

    GoogleCloudStorageItemInfo actualItem = cache.getItem(ITEM_A_A.getResourceId());

    assertNull(actualItem);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_B_A));
    assertFalse(cache.containsListRaw(BUCKET_A, PREFIX_A));
    assertTrue(cache.containsListRaw(BUCKET_B, PREFIX_A));
  }

  /** Test items can be inserted normally. */
  @Test
  public void testPutItemNormal() {
    GoogleCloudStorageItemInfo previousItem = cache.putItem(ITEM_A_A);

    assertNull(previousItem);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A));
  }

  /** Test old but valid items are overwritten and the old value is returned. */
  @Test
  public void testPutItemOverwrite() {
    cache.putItem(ITEM_A_A);

    GoogleCloudStorageItemInfo previousItem = cache.putItem(ITEM_A_A);

    assertEquals(previousItem, ITEM_A_A);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A));
  }

  /** Test old expired items are not returned. */
  @Test
  public void testPutItemOverwriteExpired() {
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_B, PREFIX_A, Lists.newArrayList(ITEM_B_A));
    ticker.setTimeMillis(11);

    GoogleCloudStorageItemInfo previousItem = cache.putItem(ITEM_A_ABA);

    assertNull(previousItem);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_ABA, ITEM_B_A));
    assertFalse(cache.containsListRaw(BUCKET_A, PREFIX_A));
    assertTrue(cache.containsListRaw(BUCKET_B, PREFIX_A));
  }

  /** Test lists can be retrieved. */
  @Test
  public void testGetListNormal() {
    List<GoogleCloudStorageItemInfo> expectedItems =
        Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B);
    cache.putItem(ITEM_B_A);
    cache.putList(BUCKET_A, "", expectedItems);

    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, "");

    assertContainsInAnyOrder(actualItems, expectedItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(),
        Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B, ITEM_B_A));
    assertTrue(cache.containsListRaw(BUCKET_A, ""));
  }

  /** Test lists can be retrieved. */
  @Test
  public void testGetListNormalAlt() {
    List<GoogleCloudStorageItemInfo> expectedItems =
        Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA);
    cache.putItem(ITEM_A_B);
    cache.putItem(ITEM_B_A);
    cache.putList(BUCKET_A, PREFIX_A, expectedItems);

    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, PREFIX_A);

    assertContainsInAnyOrder(actualItems, expectedItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(),
        Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B, ITEM_B_A));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
  }

  /** Test derived lists can be retrieved. */
  @Test
  public void testGetListDerived() {
    List<GoogleCloudStorageItemInfo> expectedItems = Lists.newArrayList(ITEM_A_AA, ITEM_A_ABA);
    cache.putItem(ITEM_B_A);
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B));

    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, PREFIX_A + "/");

    assertContainsInAnyOrder(actualItems, expectedItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(),
        Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B, ITEM_B_A));
    assertTrue(cache.containsListRaw(BUCKET_A, ""));
  }

  /** Test missing lists cannot be retrieved. */
  @Test
  public void testGetListMissing() {
    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, PREFIX_A);

    assertNull(actualItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
  }

  /** Test missing lists cannot be retrieved. */
  @Test
  public void testGetListMissingAlt() {
    cache.putItem(ITEM_A_B);
    cache.putItem(ITEM_B_A);
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));

    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, "");

    assertNull(actualItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(),
        Lists.newArrayList(ITEM_A_B, ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_B_A));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
  }

  /** Test expired lists cannot be retrieved. */
  @Test
  public void testGetListExpired() {

    cache.putItem(ITEM_B_A);
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA, ITEM_A_B));
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_AA, ITEM_A_ABA));
    ticker.setTimeMillis(11);

    List<GoogleCloudStorageItemInfo> actualItems = cache.getList(BUCKET_A, "");

    assertNull(actualItems);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_B_A));
    assertFalse(cache.containsListRaw(BUCKET_A, ""));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
  }

  /** Test lists of items can be inserted. */
  @Test
  public void testPutListNormal() {
    cache.putList(BUCKET_A, PREFIX_AA, Lists.newArrayList(ITEM_A_AA, ITEM_A_ABA));

    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_AA, ITEM_A_ABA));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_AA));
  }

  /** Test old but valid lists are overwritten when a new list is inserted. */
  @Test
  public void testPutListOverwrite() {
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_AA));

    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_AA));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
  }

  /** Test old but valid lists are overwritten when a new list is inserted. */
  @Test
  public void testPutListOverwriteAlt() {
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_AA, Lists.newArrayList(ITEM_A_AA));

    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    assertTrue(cache.containsListRaw(BUCKET_A, ""));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_AA));
  }

  /** Test items can be removed. */
  @Test
  public void testRemoveItemNormal() {
    cache.putItem(ITEM_A_A);

    GoogleCloudStorageItemInfo actualItem = cache.removeItem(ITEM_A_A.getResourceId());

    assertEquals(actualItem, ITEM_A_A);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
  }

  /** Test missing items can be removed. */
  @Test
  public void testRemoveItemNormalAlt() {
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));

    GoogleCloudStorageItemInfo actualItem = cache.removeItem(ITEM_A_AA.getResourceId());

    assertEquals(actualItem, ITEM_A_AA);
    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A, ITEM_A_ABA));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
  }

  /** Test missing items cannot be removed. */
  @Test
  public void testRemoveItemMissing() {
    GoogleCloudStorageItemInfo actualItem = cache.removeItem(ITEM_A_AA.getResourceId());

    assertNull(actualItem);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
  }

  /** Test missing items can be removed. */
  @Test
  public void testRemoveItemExpired() {
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_AA, Lists.newArrayList(ITEM_A_AA));
    ticker.setTimeMillis(11);

    GoogleCloudStorageItemInfo actualItem = cache.removeItem(ITEM_A_A.getResourceId());

    assertEquals(actualItem, null);
    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
    assertFalse(cache.containsListRaw(BUCKET_A, ""));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_AA));
  }

  @Test
  public void testInvalidateBucket() {
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA));
    cache.putList(BUCKET_B, "", Lists.newArrayList(ITEM_B_A));

    cache.invalidateBucket(BUCKET_A);

    // Verify the state of the cache.
    assertContainsInAnyOrder(cache.getAllItemsRaw(), Lists.newArrayList(ITEM_B_A));
    assertFalse(cache.containsListRaw(BUCKET_A, ""));
    assertFalse(cache.containsListRaw(BUCKET_A, PREFIX_A));
    assertTrue(cache.containsListRaw(BUCKET_B, ""));
  }

  @Test
  public void testInvalidateBucketAlt() {
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_A, Lists.newArrayList(ITEM_A_A, ITEM_A_AA));
    cache.putList(BUCKET_B, "", Lists.newArrayList(ITEM_B_A));

    cache.invalidateBucket(BUCKET_B);

    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.newArrayList(ITEM_A_A, ITEM_A_AA));
    assertTrue(cache.containsListRaw(BUCKET_A, ""));
    assertTrue(cache.containsListRaw(BUCKET_A, PREFIX_A));
    assertFalse(cache.containsListRaw(BUCKET_B, ""));
  }

  /** Test all items are cleared by invalidate all. */
  @Test
  public void testInvalidateAll() {
    cache.putList(BUCKET_A, "", Lists.newArrayList(ITEM_A_A, ITEM_A_AA, ITEM_A_ABA));
    cache.putList(BUCKET_A, PREFIX_AA, Lists.newArrayList(ITEM_A_AA));

    cache.invalidateAll();

    // Verify the state of the cache.
    assertContainsInAnyOrder(
        cache.getAllItemsRaw(), Lists.<GoogleCloudStorageItemInfo>newArrayList());
    assertFalse(cache.containsListRaw(BUCKET_A, ""));
    assertFalse(cache.containsListRaw(BUCKET_A, PREFIX_AA));
  }

  /** Ticker with a manual time value used for testing the cache. */
  private static class TestTicker extends Ticker {

    private long time;

    @Override
    public long read() {
      return time;
    }

    public void setTimeMillis(long millis) {
      time = TimeUnit.NANOSECONDS.convert(millis, TimeUnit.MILLISECONDS);
    }
  }
}
