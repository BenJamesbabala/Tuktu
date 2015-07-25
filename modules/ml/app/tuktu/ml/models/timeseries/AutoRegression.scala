package tuktu.ml.models.timeseries

/**
 * Taken from Cloudera's Spark-based Timeseries repo:
 * https://github.com/josepablocam/spark-timeseries
 */

/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

import breeze.linalg._
import org.apache.commons.math3.random.RandomGenerator
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression
import tuktu.ml.models.BaseModel

object Autoregression {
    def matToRowArrs(mat: Matrix[Double]): Array[Array[Double]] = {
        val arrs = new Array[Array[Double]](mat.rows)
        for (r <- 0 until mat.rows) {
            arrs(r) = mat(r to r, 0 to mat.cols - 1).toDenseMatrix.toArray
        }
        arrs
    }

    /**
     * Fits an AR(1) model to the given time series
     */
    def fitModel(ts: Vector[Double]): ARModel = fitModel(ts, 1)

    /**
     * Fits an AR model to the given time series.
     */
    def fitModel(ts: Vector[Double], maxLag: Int): ARModel = {
        // This is loosely based off of the implementation in statsmodels:
        // https://github.com/statsmodels/statsmodels/blob/master/statsmodels/tsa/ar_model.py

        // Make left hand side
        val Y = ts(maxLag until ts.length)
        // Make lagged right hand side
        val X = Lag.lagMatTrimBoth(ts, maxLag)

        val regression = new OLSMultipleLinearRegression()
        regression.newSampleData(Y.toArray, matToRowArrs(X))
        val params = regression.estimateRegressionParameters()
        new ARModel(params(0), params.slice(1, params.length))
    }
}

class ARModel(val c: Double, val coefficients: Array[Double]) extends BaseModel {

    def this(c: Double, coef: Double) = this(c, Array(coef))

    /**
     * {@inheritDoc}
     */
    def removeTimeDependentEffects(ts: Vector[Double], destTs: Vector[Double] = null): Vector[Double] = {
        val dest = if (destTs == null) DenseVector.zeros[Double](ts.length) else destTs
        var i = 0
        while (i < ts.length) {
            dest(i) = ts(i) - c
            var j = 0
            while (j < coefficients.length && i - j - 1 >= 0) {
                dest(i) -= ts(i - j - 1) * coefficients(j)
                j += 1
            }
            i += 1
        }
        dest
    }

    /**
     * {@inheritDoc}
     */
    def addTimeDependentEffects(ts: Vector[Double], destTs: Vector[Double]): Vector[Double] = {
        val dest = if (destTs == null) DenseVector.zeros[Double](ts.length) else destTs
        var i = 0
        while (i < ts.length) {
            dest(i) = c + ts(i)
            var j = 0
            while (j < coefficients.length && i - j - 1 >= 0) {
                dest(i) += dest(i - j - 1) * coefficients(j)
                j += 1
            }
            i += 1
        }
        dest
    }

    def sample(n: Int, rand: RandomGenerator): Vector[Double] = {
        val vec = new DenseVector(Array.fill[Double](n)(rand.nextGaussian()))
        addTimeDependentEffects(vec, vec)
    }
}
