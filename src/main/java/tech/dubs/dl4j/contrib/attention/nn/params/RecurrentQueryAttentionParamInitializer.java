/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package tech.dubs.dl4j.contrib.attention.nn.params;

import org.deeplearning4j.nn.api.ParamInitializer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.Distributions;
import org.deeplearning4j.nn.conf.layers.BaseRecurrentLayer;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.nn.weights.WeightInitUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.distribution.Distribution;

import java.util.*;

import static org.nd4j.linalg.indexing.NDArrayIndex.interval;
import static org.nd4j.linalg.indexing.NDArrayIndex.point;

/*
 * TODO: Allow configuring different weight init distribution for every weight and bias type
 */
public class RecurrentQueryAttentionParamInitializer implements ParamInitializer {

    private static final RecurrentQueryAttentionParamInitializer INSTANCE = new RecurrentQueryAttentionParamInitializer();

    public static RecurrentQueryAttentionParamInitializer getInstance() {
        return INSTANCE;
    }

    public static final String WEIGHT_KEY = DefaultParamInitializer.WEIGHT_KEY;
    public static final String RECURRENT_WEIGHT_KEY = "WR";
    public static final String BIAS_KEY = DefaultParamInitializer.BIAS_KEY;
    public static final String QUERY_WEIGHT_KEY = "WQ";
    public static final String RECURRENT_QUERY_WEIGHT_KEY = "WQR";
    public static final String QUERY_BIAS_KEY = "bQ";

    private static final List<String> PARAM_KEYS = Collections.unmodifiableList(Arrays.asList(WEIGHT_KEY, QUERY_WEIGHT_KEY, BIAS_KEY, RECURRENT_WEIGHT_KEY, RECURRENT_QUERY_WEIGHT_KEY, QUERY_BIAS_KEY));
    private static final List<String> WEIGHT_KEYS = Collections.unmodifiableList(Arrays.asList(WEIGHT_KEY, QUERY_WEIGHT_KEY, RECURRENT_WEIGHT_KEY, RECURRENT_QUERY_WEIGHT_KEY));
    private static final List<String> BIAS_KEYS = Collections.unmodifiableList(Arrays.asList(BIAS_KEY, QUERY_BIAS_KEY));


    @Override
    public long numParams(NeuralNetConfiguration conf) {
        return numParams(conf.getLayer());
    }

    @Override
    public long numParams(Layer layer) {
        BaseRecurrentLayer c = (BaseRecurrentLayer) layer;
        final long nIn = c.getNIn();
        final long nOut = c.getNOut();

        final long paramsW = nIn * nOut;
        final long paramsWR = nIn * nOut;
        final long paramsWq = nIn;
        final long paramsWqR = nOut;
        final long paramsB = nOut;
        final long paramsBq = 1;
        return paramsW + paramsWR + paramsWq + paramsWqR + paramsB + paramsBq;
    }

    @Override
    public List<String> paramKeys(Layer layer) {
        return PARAM_KEYS;
    }

    @Override
    public List<String> weightKeys(Layer layer) {
        return WEIGHT_KEYS;
    }

    @Override
    public List<String> biasKeys(Layer layer) {
        return BIAS_KEYS;
    }

    @Override
    public boolean isWeightParam(Layer layer, String key) {
        return WEIGHT_KEYS.contains(key);
    }

    @Override
    public boolean isBiasParam(Layer layer, String key) {
        return BIAS_KEYS.contains(key);
    }

    @Override
    public Map<String, INDArray> init(NeuralNetConfiguration conf, INDArray paramsView, boolean initializeParams) {
        BaseRecurrentLayer c = (BaseRecurrentLayer) conf.getLayer();
        final long nIn = c.getNIn();
        final long nOut = c.getNOut();

        Map<String, INDArray> m;

        if (initializeParams) {
            Distribution dist = Distributions.createDistribution(c.getDist());

            m = getSubsets(paramsView, nIn, nOut, false);
            INDArray w = WeightInitUtil.initWeights(nIn, nOut, new long[]{nIn, nOut}, c.getWeightInit(), dist, 'f', m.get(WEIGHT_KEY));
            m.put(WEIGHT_KEY, w);
            INDArray wq = WeightInitUtil.initWeights(nIn, 1, new long[]{nIn, 1}, c.getWeightInit(), dist, 'f', m.get(QUERY_WEIGHT_KEY));
            m.put(QUERY_WEIGHT_KEY, wq);


            WeightInit rwInit;
            Distribution rwDist = dist;
            if (c.getWeightInitRecurrent() == null) {
                rwInit = c.getWeightInit();
            } else {
                rwInit = c.getWeightInitRecurrent();
                if (c.getDistRecurrent() != null) {
                    rwDist = Distributions.createDistribution(c.getDistRecurrent());
                }
            }

            INDArray rw = WeightInitUtil.initWeights(nIn, nOut, new long[]{nIn, nOut}, rwInit, rwDist, 'f', m.get(RECURRENT_WEIGHT_KEY));
            m.put(RECURRENT_WEIGHT_KEY, rw);
            INDArray wqr = WeightInitUtil.initWeights(nOut, 1, new long[]{nOut, 1}, rwInit, rwDist, 'f', m.get(RECURRENT_QUERY_WEIGHT_KEY));
            m.put(RECURRENT_QUERY_WEIGHT_KEY, wqr);
        } else {
            m = getSubsets(paramsView, nIn, nOut, true);
        }

        for (String paramKey : PARAM_KEYS) {
            conf.addVariable(paramKey);
        }

        return m;
    }

    @Override
    public Map<String, INDArray> getGradientsFromFlattened(NeuralNetConfiguration conf, INDArray gradientView) {
        BaseRecurrentLayer c = (BaseRecurrentLayer) conf.getLayer();
        final long nIn = c.getNIn();
        final long nOut = c.getNOut();

        return getSubsets(gradientView, nIn, nOut, true);
    }

    private static Map<String, INDArray> getSubsets(INDArray in, long nIn, long nOut, boolean reshape) {
        final long endW = nIn * nOut;
        final long endWq = endW + nIn;
        final long endWR = endWq + nIn * nOut;
        final long endWqR = endWR + nOut;
        final long endB = endWqR + nOut;
        final long endBq = endB + 1;

        INDArray w = in.get(point(0), interval(0, endW));
        INDArray wq = in.get(point(0), interval(endW, endWq));
        INDArray wr = in.get(point(0), interval(endWq, endWR));
        INDArray wqr = in.get(point(0), interval(endWR, endWqR));
        INDArray b = in.get(point(0), interval(endWqR, endB));
        INDArray bq = in.get(point(0), interval(endB, endBq));

        if (reshape) {
            w = w.reshape('f', nIn, nOut);
            wr = wr.reshape('f', nIn, nOut);
            wq = wq.reshape('f', nIn, 1);
            wqr = wqr.reshape('f', nOut, 1);
            b = b.reshape('f', 1, nOut);
            bq = bq.reshape('f', 1, 1);
        }

        Map<String, INDArray> m = new LinkedHashMap<>();
        m.put(WEIGHT_KEY, w);
        m.put(QUERY_WEIGHT_KEY, wq);
        m.put(RECURRENT_WEIGHT_KEY, wr);
        m.put(RECURRENT_QUERY_WEIGHT_KEY, wqr);
        m.put(BIAS_KEY, b);
        m.put(QUERY_BIAS_KEY, bq);
        return m;
    }
}
