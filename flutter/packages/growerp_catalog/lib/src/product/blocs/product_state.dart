/*
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

part of 'product_bloc.dart';

enum ProductStatus { initial, loading, success, failure, downloaded }

class ProductState extends Equatable {
  const ProductState({
    this.status = ProductStatus.initial,
    this.products = const <Product>[],
    this.uoms = const <Uom>[],
    this.message,
    this.csvFile,
    this.hasReachedMax = false,
    this.searchString = '',
  });

  final ProductStatus status;
  final String? message;
  final String? csvFile;
  final List<Product> products;
  final List<Uom> uoms;
  final bool hasReachedMax;
  final String searchString;

  ProductState copyWith({
    ProductStatus? status,
    String? message,
    String? csvFile,
    List<Product>? products,
    List<Uom>? uoms,
    bool error = false,
    bool? hasReachedMax,
    String? searchString,
  }) {
    return ProductState(
      status: status ?? this.status,
      products: products ?? this.products,
      uoms: uoms ?? this.uoms,
      message: message,
      csvFile: csvFile,
      hasReachedMax: hasReachedMax ?? this.hasReachedMax,
      searchString: searchString ?? this.searchString,
    );
  }

  @override
  List<Object?> get props => [status, message, csvFile, products, hasReachedMax, uoms];

  @override
  String toString() =>
      '$status { #products: ${products.length}, uoms: ${uoms.length} '
      'hasReachedMax: $hasReachedMax message $message}';
}
