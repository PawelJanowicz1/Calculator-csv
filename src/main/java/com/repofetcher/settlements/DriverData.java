package com.repofetcher.settlements;

import java.math.BigDecimal;

public class DriverData {
    private final String driverIdentifier;

    private BigDecimal totalBoltNetRaw = BigDecimal.ZERO;
    private BigDecimal totalBoltCashRaw = BigDecimal.ZERO;
    private BigDecimal totalBoltTipsRaw = BigDecimal.ZERO;
    private BigDecimal totalBoltBonusOrCancelRaw = BigDecimal.ZERO;

    private BigDecimal totalUberNetRaw = BigDecimal.ZERO;
    private BigDecimal totalUberCashRaw = BigDecimal.ZERO;

    private BigDecimal totalFreeNowNetRaw = BigDecimal.ZERO;
    private BigDecimal totalFreeNowCashRaw = BigDecimal.ZERO;

    public DriverData(String driverIdentifier) {
        this.driverIdentifier = driverIdentifier;
    }

    public String getDriverIdentifier() {
        return driverIdentifier;
    }

    public void addBoltNetEarningsRaw(BigDecimal rawNet) {
        totalBoltNetRaw = totalBoltNetRaw.add(rawNet);
    }

    public void addBoltCashRaw(BigDecimal rawCash) {
        totalBoltCashRaw = totalBoltCashRaw.add(rawCash);
    }

    public void addBoltTipsRaw(BigDecimal rawTip) {
        totalBoltTipsRaw = totalBoltTipsRaw.add(rawTip);
    }

    public void addBoltBonusOrCancellationRaw(BigDecimal rawBonusOrCancel) {
        totalBoltBonusOrCancelRaw = totalBoltBonusOrCancelRaw.add(rawBonusOrCancel);
    }

    public void addUberNetEarningsRaw(BigDecimal rawNet) {
        totalUberNetRaw = totalUberNetRaw.add(rawNet);
    }

    public void addUberCashRaw(BigDecimal rawCash) {
        totalUberCashRaw = totalUberCashRaw.add(rawCash);
    }

    public void addFreeNowNetEarningsRaw(BigDecimal rawNet) {
        totalFreeNowNetRaw = totalFreeNowNetRaw.add(rawNet);
    }

    public void addFreeNowCashRaw(BigDecimal rawCash) {
        totalFreeNowCashRaw = totalFreeNowCashRaw.add(rawCash);
    }

    public BigDecimal getBoltNetRaw() {
        return totalBoltNetRaw;
    }

    public BigDecimal getBoltCashRaw() {
        return totalBoltCashRaw;
    }

    public BigDecimal getBoltTipsRaw() {
        return totalBoltTipsRaw;
    }

    public BigDecimal getBoltBonusOrCancelRaw() {
        return totalBoltBonusOrCancelRaw;
    }

    public BigDecimal getUberNetRaw() {
        return totalUberNetRaw;
    }

    public BigDecimal getUberCashRaw() {
        return totalUberCashRaw;
    }

    public BigDecimal getFreeNowNetRaw() {
        return totalFreeNowNetRaw;
    }

    public BigDecimal getFreeNowCashRaw() {
        return totalFreeNowCashRaw;
    }
}