package custom.components;

import custom.common.CommonFunction;
import custom.entity.SearchParams;
import custom.utils.MathUtil;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static custom.BaseTest.*;

@Slf4j
public class SearchCompTest {
    public static void searchCollection(SearchParams searchParams) {
        // 先search collection
        String collection = (searchParams.getCollectionName() == null ||
                searchParams.getCollectionName().equalsIgnoreCase("")) ? globalCollectionNames.get(0) : searchParams.getCollectionName();

        // 随机向量，从数据库里筛选
        log.info("从collection里捞取向量: " + 1000);
        List<BaseVector> searchBaseVectors = CommonFunction.providerSearchVectorDataset(collection, 1000);
        log.info("提供给search使用的随机向量数: " + searchBaseVectors.size());
        // 如果不随机，则随机一个
        List<BaseVector> baseVectors= CommonFunction.providerSearchVectorByNq(searchBaseVectors, searchParams.getNq());

        ArrayList<Future<SearchResult>> list = new ArrayList<>();
        ExecutorService executorService = Executors.newFixedThreadPool(searchParams.getNumConcurrency());

        float searchTotalTime;
        long startTimeTotal = System.currentTimeMillis();
        Map<String, Object> searchLevel = new HashMap<>();
        searchLevel.put("level", searchParams.getSearchLevel());
        for (int c = 0; c < searchParams.getNumConcurrency(); c++) {
            int finalC = c;
            Callable<SearchResult> callable =
                    () -> {
                        List<BaseVector> randomBaseVectors = baseVectors;
                        log.info("线程[" + finalC + "]启动...");
                        SearchResult searchResult = new SearchResult();
                        List<Integer> returnNum = new ArrayList<>();
                        List<Object> pkList=new ArrayList<>();
                        List<Float> costTime = new ArrayList<>();
                        LocalDateTime endTime = LocalDateTime.now().plusMinutes(searchParams.getRunningMinutes());
                        int printLog = 1;
                        while (LocalDateTime.now().isBefore(endTime)) {
                            if (searchParams.isRandomVector()) {
                                randomBaseVectors = CommonFunction.providerSearchVectorByNq(searchBaseVectors, searchParams.getNq());
                            }
                            long startItemTime = System.currentTimeMillis();
                            SearchResp search = milvusClientV2.search(SearchReq.builder()
                                    .topK(searchParams.getTopK())
                                    .outputFields(searchParams.getOutputs())
                                    .consistencyLevel(ConsistencyLevel.BOUNDED)
                                    .collectionName(collection)
                                    .searchParams(searchLevel)
                                    .filter(searchParams.getFilter())
                                    .data(randomBaseVectors)
                                    .build());
                            long endItemTime = System.currentTimeMillis();
                            costTime.add((float) ((endItemTime - startItemTime) / 1000.00));
                            returnNum.add(search.getSearchResults().size());
                            pkList.add(search.getSearchResults().get(0).get(0).getId());
                            if (printLog >= logInterval) {
                                log.info("线程[" + finalC + "] 已经 search :" + returnNum.size() + "次");
                                printLog = 0;
                            }
                            printLog++;
                        }
                        searchResult.setResultNum(returnNum);
                        searchResult.setCostTime(costTime);
                        searchResult.setPkList(pkList);
                        return searchResult;
                    };
            Future<SearchResult> future = executorService.submit(callable);
            list.add(future);
        }

        long requestNum = 0;
        long successNum = 0;
        List<Object> pkIdTotal=new ArrayList<>();
        List<Float> costTimeTotal = new ArrayList<>();
        for (Future<SearchResult> future : list) {
            try {
                SearchResult searchResult = future.get();
                requestNum += searchResult.getResultNum().size();
                successNum += searchResult.getResultNum().stream().filter(x -> x == searchParams.getTopK()).count();
                costTimeTotal.addAll(searchResult.getCostTime());
                pkIdTotal.addAll(searchResult.getPkList());
            } catch (InterruptedException | ExecutionException e) {
                log.error("search 统计异常:" + e.getMessage());
            }
        }
        long endTimeTotal = System.currentTimeMillis();
        searchTotalTime = (float) ((endTimeTotal - startTimeTotal) / 1000.00);

        try {
            log.info(
                    "Total search " + requestNum + "次数 ,cost: " + searchTotalTime + " seconds! pass rate:" + (float) (100.0 * successNum / requestNum) + "%");
            log.info("Total 线程数 " + searchParams.getNumConcurrency() + " ,RPS avg :" + requestNum / searchTotalTime);
            log.info("Avg:" + MathUtil.calculateAverage(costTimeTotal));
            log.info("TP99:" + MathUtil.calculateTP99(costTimeTotal, 0.99f));
            log.info("TP98:" + MathUtil.calculateTP99(costTimeTotal, 0.98f));
            log.info("TP90:" + MathUtil.calculateTP99(costTimeTotal, 0.90f));
            log.info("TP85:" + MathUtil.calculateTP99(costTimeTotal, 0.85f));
            log.info("TP80:" + MathUtil.calculateTP99(costTimeTotal, 0.80f));
            log.info("TP50:" + MathUtil.calculateTP99(costTimeTotal, 0.50f));
            long count = pkIdTotal.stream().filter(x -> recallBaseIdList.contains(x)).count();
            log.info("Recall:"+(float)(100.00*count/pkIdTotal.size())+"%");
        } catch (Exception e) {
            log.error("统计异常：" + e.getMessage());
        }
        executorService.shutdown();

    }

    @Data
    public static class SearchResult {
        private List<Float> costTime;
        private List<Integer> resultNum;
        private List<Object> pkList;
    }
}
