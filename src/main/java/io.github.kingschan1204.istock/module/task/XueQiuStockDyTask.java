package io.github.kingschan1204.istock.module.task;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mongodb.client.result.UpdateResult;
import io.github.kingschan1204.istock.common.util.cache.EhcacheUtil;
import io.github.kingschan1204.istock.common.util.stock.StockSpider;
import io.github.kingschan1204.istock.module.maindata.po.Stock;
import io.github.kingschan1204.istock.module.maindata.po.StockDyQueue;
import io.github.kingschan1204.istock.module.maindata.repository.StockDyQueueRepository;
import io.github.kingschan1204.istock.module.spider.util.TradingDateUtil;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 定时更新分红情况
 *
 * @author chenguoxiang
 * @create 2018-03-29 14:50
 **/
@Slf4j
@Component
public class XueQiuStockDyTask implements Job{

    @Autowired
    private StockSpider spider;
    @Autowired
    private MongoTemplate template;
    @Autowired
    private StockDyQueueRepository stockDyQueueRepository;
    @Autowired
    EhcacheUtil ehcacheUtil;
    final String cacheName="XueQiuStockDyTask";
    @Autowired
    private TradingDateUtil tradingDateUtil;

    /**
     * 是否错误次数过多，停止任务
     * @return
     */
    boolean stopTask(){
        return getErrorTotal()>5;
    }

    /**
     * 得到执行错误的记录
     * @return
     */
    int getErrorTotal(){
        Object value =ehcacheUtil.getKey(cacheName,"error");
        int val=null==value?0:(int)value;
        return val;
    }


    public void uptateDy(JSONObject data) {
        //受影响行
        int affected = 0;
        Integer dateNumber = Integer.valueOf(TradingDateUtil.getDateYYYYMMdd());
        JSONArray rows = data.getJSONArray("list");
        List<Stock> list = rows.toJavaList(Stock.class);
        for (Stock stock : list) {
            UpdateResult updateResult = template.updateFirst(
                    new Query(Criteria.where("_id").is(stock.getCode())),
                    new Update()
                            .set("dy", stock.getDy())
                            .set("dyDate", dateNumber),
                    "stock"
            );
            affected += updateResult.getModifiedCount();
        }
        log.info("dy 批处理：共{}条，本次更新{}条", list.size(), affected);

    }

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        if(!tradingDateUtil.isTradingDay()){
            log.info("今天非交易日...");
            return;
        }
        if(stopTask()){
            log.error("错误次数过多，不执行任务!");
            return;
        }
        Long start = System.currentTimeMillis();
        List<StockDyQueue> list = template.find(
                new Query(Criteria.where("date").is(TradingDateUtil.getDateYYYYMMdd())), StockDyQueue.class
        );
        int pageindex = 1;
        int totalpage = 1;
        if (null != list && list.size() > 0) {
            pageindex = list.size() + 1;
            totalpage = list.get(0).getTotalPage();
        }
        if (pageindex > totalpage) {
            log.debug("stock dy 已经全部更新完，当前{}页,共{}页", pageindex, totalpage);
            return;
        }
        try {
            JSONObject data = spider.getDy(pageindex);
            uptateDy(data);
            int total = data.getInteger("count");
            int pagesize = total % 100 == 0 ? total / 100 : total / 100 + 1;
            StockDyQueue stockDyQueue = new StockDyQueue();
            stockDyQueue.setDate(Integer.valueOf(TradingDateUtil.getDateYYYYMMdd()));
            stockDyQueue.setPageIndex(pageindex);
            stockDyQueue.setTotalPage(pagesize);
            template.save(stockDyQueue, "stock_dy_queue");
            log.info("dy更新第{}页,共{}页 耗时：{} ms", pageindex, totalpage, (System.currentTimeMillis() - start));
        } catch (Exception ex) {
            ehcacheUtil.addKey(cacheName,"error",getErrorTotal()+1);
            log.error("dy 出错了:{}", ex);
            ex.printStackTrace();
        }
    }
}
