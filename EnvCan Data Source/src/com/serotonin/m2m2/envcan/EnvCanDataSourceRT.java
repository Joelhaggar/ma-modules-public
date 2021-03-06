/*
    Copyright (C) 2006-2011 Serotonin Software Technologies Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.envcan;

import java.util.TimeZone;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.i18n.TranslatableException;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.rt.dataImage.DataPointRT;
import com.serotonin.m2m2.rt.dataImage.PointValueTime;
import com.serotonin.m2m2.rt.dataImage.SetPointSource;
import com.serotonin.m2m2.rt.dataSource.DataSourceRT;
import com.serotonin.m2m2.rt.dataSource.PollingDataSource;
import com.serotonin.util.XmlUtilsTS;
import com.serotonin.web.http.HttpUtils4;

/**
 * @author Matthew Lohbihler
 */
public class EnvCanDataSourceRT extends PollingDataSource {
    public static final int DATA_RETRIEVAL_FAILURE_EVENT = 1;
    public static final int PARSE_EXCEPTION_EVENT = 2;

    private final EnvCanDataSourceVO vo;
    private long nextValueTime = -1;
    private long tzOffset;

    public EnvCanDataSourceRT(EnvCanDataSourceVO vo) {
        super(vo);
        this.vo = vo;
        setPollingPeriod(Common.TimePeriods.HOURS, 1, false);
    }

    @Override
    public void removeDataPoint(DataPointRT dataPoint) {
        returnToNormal(PARSE_EXCEPTION_EVENT, System.currentTimeMillis());
        super.removeDataPoint(dataPoint);
    }

    @Override
    public void setPointValue(DataPointRT dataPoint, PointValueTime valueTime, SetPointSource source) {
        // no op
    }

    @Override
    public void initialize() {
        tzOffset = TimeZone.getDefault().getRawOffset();
    }

    @Override
    protected void doPoll(long time) {
        if (nextValueTime == -1) {
            // Determine when we should start from
            nextValueTime = System.currentTimeMillis();
            for (DataPointRT dp : dataPoints) {
                PointValueTime pvt = dp.getPointValue();
                if (pvt == null) {
                    nextValueTime = 0;
                    break;
                }
                if (nextValueTime > pvt.getTime())
                    nextValueTime = pvt.getTime();
            }

            if (nextValueTime == 0)
                nextValueTime = new DateTime(2008, 1, 1, 0, 0, 0, 0).getMillis();
            else
                nextValueTime += 1000 * 60 * 60;
        }

        long previousValueTime = nextValueTime;
        doPollImpl(time);

        while (nextValueTime != previousValueTime) {
            // Something was changed.
            DateTime prev = new DateTime(previousValueTime);
            DateTime now = new DateTime(System.currentTimeMillis());
            if (prev.getYear() < now.getYear() || prev.getMonthOfYear() < now.getMonthOfYear()) {
                previousValueTime = nextValueTime;
                doPollImpl(time);
            }
            else
                break;
        }
    }

    private void doPollImpl(long runtime) {
        DateTime dt = new DateTime(nextValueTime);
        StringBuilder url = new StringBuilder();
        url.append("http://climate.weatheroffice.gc.ca/climateData/bulkdata_e.html?StationID=").append(
                vo.getStationId());
        url.append("&Year=").append(dt.getYear());
        url.append("&Month=").append(dt.getMonthOfYear() + 1);
        url.append("&format=xml&timeframe=1");

        String data;
        try {
            data = getData(url.toString(), 30, 2);
        }
        catch (Exception e) {
            TranslatableMessage lm;
            if (e instanceof TranslatableException)
                lm = ((TranslatableException) e).getTranslatableMessage();
            else
                lm = new TranslatableMessage("envcands.retrievalError", e.getMessage());
            raiseEvent(DATA_RETRIEVAL_FAILURE_EVENT, runtime, true, lm);
            return;
        }

        // If we made it this far, everything is good.
        returnToNormal(DATA_RETRIEVAL_FAILURE_EVENT, runtime);

        try {
            Document xml = XmlUtilsTS.parse(data);
            Element climateDataElement = xml.getDocumentElement();
            for (Element stationDataElement : XmlUtilsTS.getChildElements(climateDataElement, "stationdata")) {
                int year = XmlUtilsTS.getIntAttribute(stationDataElement, "year", -1);
                int month = XmlUtilsTS.getIntAttribute(stationDataElement, "month", -1);
                int day = XmlUtilsTS.getIntAttribute(stationDataElement, "day", -1);
                int hour = XmlUtilsTS.getIntAttribute(stationDataElement, "hour", -1);

                long time = new DateTime(year, month, day, hour, 0, 0, 0, DateTimeZone.UTC).getMillis();
                time -= tzOffset;

                double temp = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "temp", Double.NaN);
                double dptemp = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "dptemp", Double.NaN);
                double visibility = XmlUtilsTS
                        .getChildElementTextAsDouble(stationDataElement, "visibility", Double.NaN);
                double relhum = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "relhum", Double.NaN);
                double winddir = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "winddir", Double.NaN);
                double windspd = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "windspd", Double.NaN);
                double stnpress = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "stnpress", Double.NaN);
                double humidex = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "humidex", Double.NaN);
                double windchill = XmlUtilsTS.getChildElementTextAsDouble(stationDataElement, "windchill", Double.NaN);
                String weather = XmlUtilsTS.getChildElementText(stationDataElement, "weather");

                if (Double.isNaN(temp))
                    // If there is no temp value, ignore the record
                    continue;

                // Update the next value time.
                nextValueTime = time + 1000 * 60 * 60;

                for (DataPointRT dp : dataPoints) {
                    PointValueTime pvt = dp.getPointValue();
                    if (pvt != null && pvt.getTime() >= time)
                        // This value is in the past. Ignore it.
                        continue;

                    EnvCanPointLocatorVO plvo = dp.getVO().getPointLocator();
                    if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.TEMP && !Double.isNaN(temp))
                        pvt = new PointValueTime(temp, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.DEW_POINT_TEMP
                            && !Double.isNaN(dptemp))
                        pvt = new PointValueTime(dptemp, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.REL_HUM && !Double.isNaN(relhum))
                        pvt = new PointValueTime(relhum, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.WIND_DIR
                            && !Double.isNaN(winddir))
                        pvt = new PointValueTime(winddir, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.WIND_SPEED
                            && !Double.isNaN(windspd))
                        pvt = new PointValueTime(windspd, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.VISIBILITY
                            && !Double.isNaN(visibility))
                        pvt = new PointValueTime(visibility, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.STN_PRESS
                            && !Double.isNaN(stnpress))
                        pvt = new PointValueTime(stnpress, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.HUMIDEX && !Double.isNaN(humidex))
                        pvt = new PointValueTime(humidex, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.WIND_CHILL
                            && !Double.isNaN(windchill))
                        pvt = new PointValueTime(windchill, time);
                    else if (plvo.getAttributeId() == EnvCanPointLocatorVO.Attributes.WEATHER && weather != null)
                        pvt = new PointValueTime(weather, time);
                    else
                        continue;

                    dp.updatePointValue(pvt);
                }
            }

            returnToNormal(PARSE_EXCEPTION_EVENT, runtime);
        }
        catch (SAXException e) {
            raiseEvent(PARSE_EXCEPTION_EVENT, runtime, true, DataSourceRT.getExceptionMessage(e));
        }
    }

    private String getData(String url, int timeoutSeconds, int retries) throws TranslatableException {
        // Try to get the data.
        String data;
        while (true) {
            HttpClient client = Common.getHttpClient(timeoutSeconds * 1000);
            HttpGet request = null;
            TranslatableMessage message;

            try {
                request = new HttpGet(url);
                HttpResponse response = client.execute(request);
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    data = HttpUtils4.readResponseBody(response);
                    break;
                }
                message = new TranslatableMessage("event.http.response", url, response.getStatusLine().getStatusCode());
            }
            catch (Exception e) {
                message = DataSourceRT.getExceptionMessage(e);
            }
            finally {
                request.reset();
            }

            if (retries <= 0)
                throw new TranslatableException(message);
            retries--;

            // Take a little break instead of trying again immediately.
            try {
                Thread.sleep(10000);
            }
            catch (InterruptedException e) {
                // no op
            }
        }

        return data;
    }
}
